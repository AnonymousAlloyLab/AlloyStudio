package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Objects;

/** Immutable complete state image captured at one successful transition boundary. */
public final class CertificateTraceSnapshot {
    private final long revision;
    private final GraphStatus status;
    private final Map<EClassId, TypedEClassRecord> classes;
    private final Map<EClassId, ParentAssignment> parents;
    private final Map<CanonicalShape, Set<EClassId>> hashCons;
    private final Map<ParentRecordKey, TypedEqualityCertificate> shapeCertificates;
    private final Map<ParentRecordKey, CertificateTracePayload.ShapeRecord>
            liveShapeRecords;
    private final Map<ParentRecordKey, RetiredShapeRecordCertificate>
            retiredShapeRecords;
    private final Map<EClassId, Set<ParentRecordKey>> parentUses;
    private final Set<ParentRecordKey> dirtyParents;
    private final Map<EClassId, List<InterfaceRestrictionCertificate>> restrictions;
    private final Map<EClassId, CertifiedInsertionResult> insertions;
    private final StructuralKey stateKey;

    CertificateTraceSnapshot(
            long revision,
            GraphStatus status,
            Map<EClassId, TypedEClassRecord> classes,
            Map<EClassId, ParentAssignment> parents,
            Map<CanonicalShape, ? extends Set<EClassId>> hashCons,
            Map<ParentRecordKey, TypedEqualityCertificate> shapeCertificates,
            Map<ParentRecordKey, RetiredShapeRecordCertificate> retiredShapeRecords,
            Map<EClassId, ? extends Set<ParentRecordKey>> parentUses,
            Set<ParentRecordKey> dirtyParents,
            Map<EClassId, ? extends List<InterfaceRestrictionCertificate>> restrictions,
            Map<EClassId, CertifiedInsertionResult> insertions) {
        if (revision < 0) {
            throw new IllegalArgumentException("A trace snapshot has negative revision");
        }
        this.revision = revision;
        this.status = Objects.requireNonNull(status, "status");
        this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        this.parents = Collections.unmodifiableMap(new LinkedHashMap<>(parents));
        Map<CanonicalShape, Set<EClassId>> hashCopies = new LinkedHashMap<>();
        for (Map.Entry<CanonicalShape, ? extends Set<EClassId>> entry
                : hashCons.entrySet()) {
            if (entry.getValue().isEmpty()) {
                throw new IllegalArgumentException(
                        "A hash-cons ownership bucket must be nonempty");
            }
            hashCopies.put(entry.getKey(), Collections.unmodifiableSet(
                    new LinkedHashSet<>(entry.getValue())));
        }
        this.hashCons = Collections.unmodifiableMap(hashCopies);
        this.shapeCertificates = Collections.unmodifiableMap(
                new LinkedHashMap<>(shapeCertificates));
        Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> liveRecords =
                new LinkedHashMap<>();
        for (Map.Entry<EClassId, TypedEClassRecord> classEntry
                : this.classes.entrySet()) {
            TypedEClassRecord record = classEntry.getValue();
            for (Map.Entry<CanonicalShape, ShapeWitness> shapeEntry
                    : record.shapeWitnesses().entrySet()) {
                ParentRecordKey key = new ParentRecordKey(
                        classEntry.getKey(), shapeEntry.getKey());
                TypedEqualityCertificate equation = this.shapeCertificates.get(key);
                if (equation == null) {
                    throw new IllegalArgumentException(
                            "A trace snapshot cannot omit a live shape equation");
                }
                liveRecords.put(key, new CertificateTracePayload.ShapeRecord(
                        key,
                        record.interfaceView(),
                        shapeEntry.getValue(),
                        equation));
            }
        }
        if (!liveRecords.keySet().equals(this.shapeCertificates.keySet())) {
            throw new IllegalArgumentException(
                    "A trace snapshot has orphan or missing shape equations");
        }
        this.liveShapeRecords = Collections.unmodifiableMap(liveRecords);
        Map<ParentRecordKey, RetiredShapeRecordCertificate> retiredCopies =
                new LinkedHashMap<>();
        for (Map.Entry<ParentRecordKey, RetiredShapeRecordCertificate> entry
                : retiredShapeRecords.entrySet()) {
            RetiredShapeRecordCertificate retirement = Objects.requireNonNull(
                    entry.getValue(), "retirement");
            retirement.verify();
            if (!entry.getKey().equals(retirement.retiredRecord())) {
                throw new IllegalArgumentException(
                        "A retirement is indexed by another retired shape record");
            }
            retiredCopies.put(entry.getKey(), retirement);
        }
        this.retiredShapeRecords = Collections.unmodifiableMap(retiredCopies);
        Map<EClassId, Set<ParentRecordKey>> useCopies = new LinkedHashMap<>();
        for (Map.Entry<EClassId, ? extends Set<ParentRecordKey>> entry
                : parentUses.entrySet()) {
            if (entry.getValue().isEmpty()) {
                throw new IllegalArgumentException(
                        "A trace snapshot cannot retain an empty parent-use bucket");
            }
            useCopies.put(entry.getKey(), Collections.unmodifiableSet(
                    new LinkedHashSet<>(entry.getValue())));
        }
        this.parentUses = Collections.unmodifiableMap(useCopies);
        this.dirtyParents = Collections.unmodifiableSet(
                new LinkedHashSet<>(dirtyParents));
        Map<EClassId, List<InterfaceRestrictionCertificate>> restrictionCopies =
                new LinkedHashMap<>();
        for (Map.Entry<EClassId, ? extends List<InterfaceRestrictionCertificate>> entry
                : restrictions.entrySet()) {
            if (entry.getValue().isEmpty()) {
                throw new IllegalArgumentException(
                        "A trace snapshot cannot retain an empty restriction history");
            }
            restrictionCopies.put(entry.getKey(), Collections.unmodifiableList(
                    new ArrayList<>(entry.getValue())));
        }
        this.restrictions = Collections.unmodifiableMap(restrictionCopies);
        this.insertions = Collections.unmodifiableMap(new LinkedHashMap<>(insertions));
        validateSnapshotIndexes();
        this.stateKey = buildStateKey();
    }

    public long revision() {
        return revision;
    }

    public GraphStatus status() {
        return status;
    }

    public Map<EClassId, TypedEClassRecord> classes() {
        return classes;
    }

    public Map<EClassId, ParentAssignment> parents() {
        return parents;
    }

    public Map<CanonicalShape, Set<EClassId>> hashCons() {
        return hashCons;
    }

    public Map<ParentRecordKey, TypedEqualityCertificate> shapeCertificates() {
        return shapeCertificates;
    }

    public Map<ParentRecordKey, CertificateTracePayload.ShapeRecord>
            liveShapeRecords() {
        return liveShapeRecords;
    }

    public Map<ParentRecordKey, RetiredShapeRecordCertificate> retiredShapeRecords() {
        return retiredShapeRecords;
    }

    public Map<EClassId, Set<ParentRecordKey>> parentUses() {
        return parentUses;
    }

    public Set<ParentRecordKey> dirtyParents() {
        return dirtyParents;
    }

    public Map<EClassId, List<InterfaceRestrictionCertificate>> restrictions() {
        return restrictions;
    }

    public Map<EClassId, CertifiedInsertionResult> insertions() {
        return insertions;
    }

    public StructuralKey stateKey() {
        return stateKey;
    }

    private void validateSnapshotIndexes() {
        if (!classes.keySet().equals(parents.keySet())) {
            throw new IllegalArgumentException(
                    "A trace snapshot must retain one parent assignment per e-class");
        }
        Map<EClassId, EClassId> directParents = directParents(parents);
        for (EClassId id : classes.keySet()) {
            rootOf(id, directParents);
        }
        for (Map.Entry<EClassId, TypedEClassRecord> entry : classes.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) {
                throw new IllegalArgumentException(
                        "A trace snapshot class is indexed by another id");
            }
            ParentAssignment assignment = parents.get(entry.getKey());
            if (!entry.getValue().interfaceView().equals(assignment.child())) {
                throw new IllegalArgumentException(
                        "A trace snapshot parent assignment uses stale child metadata");
            }
            if (!assignment.isRoot()
                    && !assignment.provenancePath().hasCertificates()) {
                throw new IllegalArgumentException(
                        "A trace snapshot non-root assignment lacks certified provenance");
            }
            TypedEClassRecord parent = classes.get(
                    assignment.parentInvocation().eclass().id());
            if (parent == null || !parent.interfaceView().equals(
                    assignment.parentInvocation().eclass())) {
                throw new IllegalArgumentException(
                        "A trace snapshot parent assignment targets stale or absent metadata");
            }
            for (ParentStep step : assignment.provenancePath().steps()) {
                TypedEClassRecord stepChild = classes.get(step.child().id());
                TypedEClassRecord stepParent = classes.get(step.parent().id());
                if (!step.hasCertificate()
                        || stepChild == null
                        || stepParent == null
                        || !stepChild.interfaceView().equals(step.child())
                        || !stepParent.interfaceView().equals(step.parent())
                        || !rootOf(step.child().id(), directParents).equals(
                                rootOf(step.parent().id(), directParents))) {
                    throw new IllegalArgumentException(
                            "A trace snapshot parent path contradicts current certified topology");
                }
            }
        }
        if (status == GraphStatus.QUIESCENT && !dirtyParents.isEmpty()) {
            throw new IllegalArgumentException(
                    "A quiescent trace snapshot cannot retain dirty records");
        }
        for (ParentRecordKey dirty : dirtyParents) {
            if (!liveShapeRecords.containsKey(dirty)) {
                throw new IllegalArgumentException(
                        "A dirty trace record must still be live");
            }
        }
        validateHistories();
        validateRetirements(directParents);
        Map<CanonicalShape, Set<EClassId>> expectedHash = new TreeMap<>();
        for (ParentRecordKey key : liveShapeRecords.keySet()) {
            ParentAssignment assignment = parents.get(key.owner());
            if (assignment != null && assignment.isRoot()) {
                expectedHash.computeIfAbsent(
                        key.shape(), ignored -> new TreeSet<>()).add(key.owner());
            }
        }
        if (!expectedHash.equals(hashCons)) {
            throw new IllegalArgumentException(
                    "A trace snapshot hash-cons index is not exact");
        }
        Map<EClassId, Set<ParentRecordKey>> expectedUses = new TreeMap<>();
        for (ParentRecordKey key : liveShapeRecords.keySet()) {
            validateStoredInvocations(key, key.shape().node(), directParents);
            Set<EClassId> children = new TreeSet<>();
            collectInvocationIds(key.shape().node(), children);
            for (EClassId child : children) {
                expectedUses.computeIfAbsent(
                        child, ignored -> new TreeSet<>()).add(key);
            }
        }
        Map<EClassId, Set<ParentRecordKey>> observedUses = new TreeMap<>();
        for (Map.Entry<EClassId, Set<ParentRecordKey>> entry : parentUses.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                observedUses.put(entry.getKey(), new TreeSet<>(entry.getValue()));
            }
        }
        if (!expectedUses.equals(observedUses)) {
            throw new IllegalArgumentException(
                    "A trace snapshot reverse parent-use index is not exact");
        }
    }

    private void validateHistories() {
        for (Map.Entry<EClassId, CertifiedInsertionResult> entry
                : insertions.entrySet()) {
            CertifiedInsertionResult insertion = Objects.requireNonNull(
                    entry.getValue(), "insertion");
            TypedEClassRecord current = classes.get(entry.getKey());
            if (current == null
                    || !entry.getKey().equals(insertion.insertedClass().id())
                    || !current.outputType().equals(
                            insertion.insertedClass().outputType())) {
                throw new IllegalArgumentException(
                        "A trace snapshot insertion history is orphaned or misindexed");
            }
            requireReachableCurrentInterface(
                    insertion.insertedClass(), current.interfaceView());
        }
        for (Map.Entry<EClassId, List<InterfaceRestrictionCertificate>> entry
                : restrictions.entrySet()) {
            TypedEClassRecord current = classes.get(entry.getKey());
            if (current == null) {
                throw new IllegalArgumentException(
                        "A trace snapshot restriction history names an absent e-class");
            }
            TypedEClassInterface previous = entry.getValue().get(0).originalInterface();
            if (!previous.id().equals(entry.getKey())) {
                throw new IllegalArgumentException(
                        "A trace snapshot restriction history is indexed by another e-class");
            }
            for (InterfaceRestrictionCertificate restriction : entry.getValue()) {
                CertificateVerifier.verifyInterfaceRestriction(restriction);
                if (!previous.equals(restriction.originalInterface())) {
                    throw new IllegalArgumentException(
                            "A trace snapshot restriction history is not composable");
                }
                previous = restriction.restrictedInterface();
            }
            if (!previous.equals(current.interfaceView())) {
                throw new IllegalArgumentException(
                        "A trace snapshot restriction history does not reach current metadata");
            }
        }
    }

    private void requireReachableCurrentInterface(
            TypedEClassInterface historical,
            TypedEClassInterface current) {
        if (historical.equals(current)) {
            return;
        }
        TypedEClassInterface reached = historical;
        for (InterfaceRestrictionCertificate restriction
                : restrictions.getOrDefault(historical.id(), Collections.emptyList())) {
            if (restriction.originalInterface().equals(reached)) {
                reached = restriction.restrictedInterface();
            }
        }
        if (!reached.equals(current)) {
            throw new IllegalArgumentException(
                    "Historical e-class metadata lacks a certified path to current metadata");
        }
    }

    private void validateRetirements(Map<EClassId, EClassId> directParents) {
        for (RetiredShapeRecordCertificate retirement
                : retiredShapeRecords.values()) {
            if (!classes.containsKey(retirement.retiredRecord().owner())
                    || !classes.containsKey(retirement.retainedRecord().owner())
                    || liveShapeRecords.containsKey(retirement.retiredRecord())) {
                throw new IllegalArgumentException(
                        "A trace snapshot retirement is orphaned or still live");
            }
            if (retirement.cause()
                            == RetiredShapeRecordCertificate.Cause.OWNER_UNION
                    && rootOf(retirement.retiredRecord().owner(), directParents).equals(
                            retirement.retiredRecord().owner())) {
                throw new IllegalArgumentException(
                        "An owner-union retirement still belongs to a leader");
            }
            requireRetirementTerminal(
                    retirement.retainedRecord(), new LinkedHashSet<>());
        }
    }

    private void requireRetirementTerminal(
            ParentRecordKey key,
            Set<ParentRecordKey> visiting) {
        if (liveShapeRecords.containsKey(key)) {
            return;
        }
        if (!visiting.add(key)) {
            throw new IllegalArgumentException(
                    "A trace snapshot retirement chain contains a cycle");
        }
        RetiredShapeRecordCertificate next = retiredShapeRecords.get(key);
        if (next == null) {
            throw new IllegalArgumentException(
                    "A trace snapshot retirement has no retained live successor");
        }
        requireRetirementTerminal(next.retainedRecord(), visiting);
        visiting.remove(key);
    }

    private void validateStoredInvocations(
            ParentRecordKey owner,
            TypedENode node,
            Map<EClassId, EClassId> directParents) {
        for (PortValue port : node.ports()) {
            validateStoredInvocations(owner, port, directParents);
        }
    }

    private void validateStoredInvocations(
            ParentRecordKey owner,
            PortValue port,
            Map<EClassId, EClassId> directParents) {
        if (port instanceof OnePort) {
            PortLeaf leaf = ((OnePort) port).leaf();
            if (leaf instanceof InvocationPortLeaf) {
                TypedInvocation invocation = ((InvocationPortLeaf) leaf).invocation();
                TypedEClassRecord current = classes.get(invocation.eclass().id());
                if (current == null
                        || !current.outputType().equals(invocation.outputType())) {
                    throw new IllegalArgumentException(
                            "A trace snapshot live shape invokes an absent or ill-typed e-class");
                }
                requireReachableCurrentInterface(
                        invocation.eclass(), current.interfaceView());
                boolean currentMetadata = current.interfaceView().equals(
                        invocation.eclass());
                boolean leader = rootOf(current.id(), directParents).equals(current.id());
                if ((status == GraphStatus.QUIESCENT
                                && (!currentMetadata || !leader))
                        || ((!currentMetadata || !leader)
                                && !dirtyParents.contains(owner))) {
                    throw new IllegalArgumentException(
                            "A trace snapshot stale invocation lacks its dirty repair obligation");
                }
            }
            return;
        }
        if (port instanceof SeqPort) {
            for (PortValue child : ((SeqPort) port).elements()) {
                validateStoredInvocations(owner, child, directParents);
            }
        } else if (port instanceof BagPort) {
            for (PortValue child : ((BagPort) port).occurrences()) {
                validateStoredInvocations(owner, child, directParents);
            }
        } else if (port instanceof SetPort) {
            for (PortValue child : ((SetPort) port).elements()) {
                validateStoredInvocations(owner, child, directParents);
            }
        } else if (port instanceof BindPort) {
            validateStoredInvocations(owner, ((BindPort) port).body(), directParents);
        } else if (port instanceof BindBlockPort) {
            validateStoredInvocations(
                    owner, ((BindBlockPort) port).body(), directParents);
        } else {
            throw new IllegalArgumentException(
                    "A trace snapshot contains an unsupported port value");
        }
    }

    private StructuralKey buildStateKey() {
        List<StructuralKey> parts = new ArrayList<>();
        parts.add(StructuralKey.leaf("snapshot-revision", Long.toString(revision)));
        parts.add(StructuralKey.leaf("snapshot-status", status.name()));
        for (Map.Entry<EClassId, TypedEClassRecord> entry
                : new TreeMap<>(classes).entrySet()) {
            parts.add(StructuralKey.branch(
                    "snapshot-class", List.of(entry.getValue().structuralKey())));
        }
        for (Map.Entry<EClassId, ParentAssignment> entry
                : new TreeMap<>(parents).entrySet()) {
            parts.add(StructuralKey.branch(
                    "snapshot-parent", List.of(entry.getValue().structuralKey())));
        }
        for (Map.Entry<CanonicalShape, Set<EClassId>> entry
                : new TreeMap<>(hashCons).entrySet()) {
            for (EClassId owner : new TreeSet<>(entry.getValue())) {
                parts.add(StructuralKey.of(
                        "snapshot-hash-owner",
                        List.of(Long.toString(owner.value())),
                        List.of(entry.getKey().structuralKey())));
            }
        }
        for (Map.Entry<ParentRecordKey, TypedEqualityCertificate> entry
                : new TreeMap<>(shapeCertificates).entrySet()) {
            parts.add(StructuralKey.branch(
                    "snapshot-shape-equation",
                    List.of(entry.getKey().structuralKey(),
                            entry.getValue().structuralKey())));
        }
        for (Map.Entry<ParentRecordKey, RetiredShapeRecordCertificate> entry
                : new TreeMap<>(retiredShapeRecords).entrySet()) {
            parts.add(StructuralKey.branch(
                    "snapshot-retirement",
                    List.of(entry.getKey().structuralKey(),
                            entry.getValue().structuralKey())));
        }
        for (Map.Entry<EClassId, Set<ParentRecordKey>> entry
                : new TreeMap<>(parentUses).entrySet()) {
            for (ParentRecordKey use : new TreeSet<>(entry.getValue())) {
                parts.add(StructuralKey.of(
                        "snapshot-parent-use",
                        List.of(Long.toString(entry.getKey().value())),
                        List.of(use.structuralKey())));
            }
        }
        for (ParentRecordKey dirty : new TreeSet<>(dirtyParents)) {
            parts.add(StructuralKey.branch(
                    "snapshot-dirty", List.of(dirty.structuralKey())));
        }
        for (Map.Entry<EClassId, List<InterfaceRestrictionCertificate>> entry
                : new TreeMap<>(restrictions).entrySet()) {
            for (int index = 0; index < entry.getValue().size(); index++) {
                parts.add(StructuralKey.of(
                        "snapshot-restriction",
                        List.of(Long.toString(entry.getKey().value()),
                                Integer.toString(index)),
                        List.of(entry.getValue().get(index).structuralKey())));
            }
        }
        for (Map.Entry<EClassId, CertifiedInsertionResult> entry
                : new TreeMap<>(insertions).entrySet()) {
            parts.add(StructuralKey.of(
                    "snapshot-insertion",
                    List.of(Long.toString(entry.getKey().value())),
                    List.of(entry.getValue().structuralKey())));
        }
        return StructuralKey.branch("certificate-trace-snapshot-v2", parts);
    }

    private static void collectInvocationIds(TypedENode node, Set<EClassId> target) {
        for (PortValue port : node.ports()) {
            collectInvocationIds(port, target);
        }
    }

    private static void collectInvocationIds(PortValue port, Set<EClassId> target) {
        if (port instanceof OnePort) {
            PortLeaf leaf = ((OnePort) port).leaf();
            if (leaf instanceof InvocationPortLeaf) {
                target.add(((InvocationPortLeaf) leaf).invocation().eclass().id());
            }
            return;
        }
        if (port instanceof SeqPort) {
            for (PortValue child : ((SeqPort) port).elements()) {
                collectInvocationIds(child, target);
            }
        } else if (port instanceof BagPort) {
            for (PortValue child : ((BagPort) port).occurrences()) {
                collectInvocationIds(child, target);
            }
        } else if (port instanceof SetPort) {
            for (PortValue child : ((SetPort) port).elements()) {
                collectInvocationIds(child, target);
            }
        } else if (port instanceof BindPort) {
            collectInvocationIds(((BindPort) port).body(), target);
        } else if (port instanceof BindBlockPort) {
            collectInvocationIds(((BindBlockPort) port).body(), target);
        }
    }

    /** Replays the payload's shape dispositions and checks the exact post-state. */
    void verifyConservationTo(
            CertificateTraceSnapshot after,
            CertificateTracePayload payload) {
        java.util.Objects.requireNonNull(after, "after");
        java.util.Objects.requireNonNull(payload, "payload");
        List<CertificateTracePayload.Union> unions = unions(payload);
        verifyClassDomain(after, payload);
        verifyUnionTopology(after, unions, payload);
        verifyRetirementLedger(after, payload, unions);
        Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> replay =
                new LinkedHashMap<>(liveShapeRecords);
        if (payload instanceof CertificateTracePayload.Insertion) {
            CertificateTracePayload.Insertion insertion =
                    (CertificateTracePayload.Insertion) payload;
            CertifiedInsertionResult result = insertion.result();
            ParentRecordKey key = new ParentRecordKey(
                    result.insertedClass().id(), result.canonicalization().shape());
            putFresh(replay, new CertificateTracePayload.ShapeRecord(
                    key,
                    result.insertedClass(),
                    result.shapeWitness(),
                    result.shapeEquation()));
            applyUnions(replay, insertion.generatedSubtransitions());
        } else if (payload instanceof CertificateTracePayload.Union) {
            applyUnion(replay, (CertificateTracePayload.Union) payload);
        } else if (payload instanceof CertificateTracePayload.RebuildRecord) {
            CertificateTracePayload.RebuildRecord rebuild =
                    (CertificateTracePayload.RebuildRecord) payload;
            if (!rebuild.hasExactEvidence()) {
                throw new IllegalStateException(
                        "A producer rebuild event lacks exact conservation evidence");
            }
            removeExact(replay, rebuild.originalRecord());
            if (rebuild.replacementRecord() != null) {
                putFresh(replay, rebuild.replacementRecord());
            } else {
                requireRetirementTarget(replay, rebuild.retirement());
            }
            applyUnions(replay, rebuild.generatedSubtransitions());
        } else if (payload instanceof CertificateTracePayload.Restriction) {
            InterfaceRestrictionCertificate restriction =
                    ((CertificateTracePayload.Restriction) payload).certificate();
            EClassId restricted = restriction.originalInterface().id();
            for (ParentRecordKey key : new ArrayList<>(replay.keySet())) {
                if (key.owner().equals(restricted)) {
                    CertificateTracePayload.ShapeRecord original = replay.get(key);
                    ShapeWitness transported = restriction
                            .transportedShapeWitnesses().get(key.shape());
                    if (transported == null) {
                        throw new IllegalStateException(
                                "Interface restriction omitted a transported shape witness");
                    }
                    TypedEmbedding oldInterfaceInAmbient = TypedEmbedding.inclusion(
                            original.owner().exposedSlots(),
                            original.witness().ambientSupport());
                    TypedEqualityCertificate factorization = EqualityCertificates.rename(
                            restriction.factorization(), oldInterfaceInAmbient);
                    TypedEqualityCertificate equation = EqualityCertificates.transitive(
                            original.ownerEquation(), factorization);
                    replay.put(key, new CertificateTracePayload.ShapeRecord(
                            key,
                            restriction.restrictedInterface(),
                            transported,
                            equation));
                }
            }
        }
        if (!replay.equals(after.liveShapeRecords)) {
            throw new IllegalStateException(
                    "Trace payload does not conserve the exact live shape-record set");
        }
        verifyOrdinaryTransitionState(after, payload);
        if (payload instanceof CertificateTracePayload.Restriction) {
            verifyRestrictionTransition(
                    after,
                    ((CertificateTracePayload.Restriction) payload).certificate());
        } else if (payload instanceof CertificateTracePayload.Symmetry) {
            verifySymmetryTransition(
                    after, (CertificateTracePayload.Symmetry) payload);
        } else if (payload instanceof CertificateTracePayload.PathCompression) {
            verifyPathCompressionTransition(
                    after, (CertificateTracePayload.PathCompression) payload);
        } else if (payload instanceof CertificateTracePayload.RebuildStart) {
            verifyRebuildStart(
                    after, (CertificateTracePayload.RebuildStart) payload);
        } else if (payload instanceof CertificateTracePayload.RebuildComplete) {
            verifyRebuildCompletion(
                    after, (CertificateTracePayload.RebuildComplete) payload);
        } else {
            verifyHistoryFrames(after, payload);
        }
    }

    private void verifyHistoryFrames(
            CertificateTraceSnapshot after,
            CertificateTracePayload payload) {
        if (!restrictions.equals(after.restrictions)) {
            throw new IllegalStateException(
                    "A non-restriction transition mutated restriction history");
        }
        Map<EClassId, CertifiedInsertionResult> expectedInsertions =
                new LinkedHashMap<>(insertions);
        if (payload instanceof CertificateTracePayload.Insertion) {
            CertifiedInsertionResult insertion =
                    ((CertificateTracePayload.Insertion) payload).result();
            CertifiedInsertionResult prior = expectedInsertions.putIfAbsent(
                    insertion.insertedClass().id(), insertion);
            if (prior != null) {
                throw new IllegalStateException(
                        "An insertion transition duplicates source provenance");
            }
            if (after.revision != Math.incrementExact(revision)
                    || status != GraphStatus.QUIESCENT
                    || after.status != (((CertificateTracePayload.Insertion) payload)
                            .generatedSubtransitions().isEmpty()
                                    ? GraphStatus.QUIESCENT : GraphStatus.DIRTY)) {
                throw new IllegalStateException(
                        "Insertion has inconsistent status or revision effects");
            }
        } else if (payload instanceof CertificateTracePayload.RebuildRecord) {
            if (after.revision != revision
                    || status != GraphStatus.DIRTY
                    || after.status != GraphStatus.DIRTY) {
                throw new IllegalStateException(
                        "One rebuild-record event must stay within one dirty revision");
            }
        } else if (payload instanceof CertificateTracePayload.Union) {
            CertificateTracePayload.Union union =
                    (CertificateTracePayload.Union) payload;
            long expectedRevision = union.revisionIncrement()
                    ? Math.incrementExact(revision) : revision;
            if (after.status != GraphStatus.DIRTY
                    || after.revision != expectedRevision) {
                throw new IllegalStateException(
                        "Union has inconsistent status or revision effects");
            }
        }
        if (!expectedInsertions.equals(after.insertions)) {
            throw new IllegalStateException(
                    "Trace transition mutated insertion provenance unexpectedly");
        }
    }

    private void verifyOrdinaryTransitionState(
            CertificateTraceSnapshot after,
            CertificateTracePayload payload) {
        if (!(payload instanceof CertificateTracePayload.Insertion)
                && !(payload instanceof CertificateTracePayload.Union)
                && !(payload instanceof CertificateTracePayload.RebuildRecord)) {
            return;
        }

        Map<EClassId, TypedEClassRecord> expectedClasses =
                new LinkedHashMap<>(classes);
        Map<EClassId, ParentAssignment> expectedParents =
                new LinkedHashMap<>(parents);
        Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> expectedRecords =
                new LinkedHashMap<>(liveShapeRecords);
        Set<ParentRecordKey> expectedDirty = new LinkedHashSet<>(dirtyParents);

        if (payload instanceof CertificateTracePayload.Insertion) {
            CertificateTracePayload.Insertion insertion =
                    (CertificateTracePayload.Insertion) payload;
            CertifiedInsertionResult result = insertion.result();
            TypedEClassInterface fresh = result.insertedClass();
            CanonicalShape shape = result.canonicalization().shape();
            ParentRecordKey key = new ParentRecordKey(fresh.id(), shape);
            CertificateTracePayload.ShapeRecord record =
                    new CertificateTracePayload.ShapeRecord(
                            key, fresh, result.shapeWitness(), result.shapeEquation());
            if (expectedClasses.putIfAbsent(
                            fresh.id(),
                            TypedEClassRecord.of(
                                    fresh,
                                    Collections.singletonMap(
                                            shape, result.shapeWitness()),
                                    TypedSymmetryGroup.identity(
                                            fresh.exposedSlots())))
                    != null
                    || expectedParents.putIfAbsent(
                            fresh.id(), ParentAssignment.root(fresh)) != null) {
                throw new IllegalStateException(
                        "An insertion transition reuses existing class state");
            }
            putFresh(expectedRecords, record);
            for (CertificateTracePayload.Union union
                    : insertion.generatedSubtransitions()) {
                applyUnionState(
                        expectedClasses,
                        expectedParents,
                        expectedRecords,
                        expectedDirty,
                        union);
            }
            compressAll(expectedParents);
        } else if (payload instanceof CertificateTracePayload.Union) {
            applyUnionState(
                    expectedClasses,
                    expectedParents,
                    expectedRecords,
                    expectedDirty,
                    (CertificateTracePayload.Union) payload);
        } else {
            CertificateTracePayload.RebuildRecord rebuild =
                    (CertificateTracePayload.RebuildRecord) payload;
            if (!expectedDirty.remove(rebuild.original())) {
                throw new IllegalStateException(
                        "A rebuild record was not selected from the exact dirty queue");
            }
            removeExact(expectedRecords, rebuild.originalRecord());
            if (rebuild.replacementRecord() != null) {
                putFresh(expectedRecords, rebuild.replacementRecord());
            } else {
                requireRetirementTarget(expectedRecords, rebuild.retirement());
            }
            expectedClasses = recordsWithCurrentClassState(
                    expectedClasses, expectedRecords);
            for (CertificateTracePayload.Union union
                    : rebuild.generatedSubtransitions()) {
                applyUnionState(
                        expectedClasses,
                        expectedParents,
                        expectedRecords,
                        expectedDirty,
                        union);
            }
        }

        expectedClasses = recordsWithCurrentClassState(
                expectedClasses, expectedRecords);
        if (!expectedClasses.equals(after.classes)
                || !expectedParents.equals(after.parents)
                || !expectedDirty.equals(after.dirtyParents)) {
            throw new IllegalStateException(
                    "Trace payload does not derive the complete class, parent, and dirty state");
        }
    }

    private static void applyUnionState(
            Map<EClassId, TypedEClassRecord> expectedClasses,
            Map<EClassId, ParentAssignment> expectedParents,
            Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> expectedRecords,
            Set<ParentRecordKey> expectedDirty,
            CertificateTracePayload.Union union) {
        ParentEdgeCertificate edge = union.certificate();
        TypedEClassRecord child = expectedClasses.get(edge.child().id());
        TypedEClassRecord parent = expectedClasses.get(edge.parent().id());
        if (child == null || parent == null
                || !child.interfaceView().equals(edge.child())
                || !parent.interfaceView().equals(edge.parent())) {
            throw new IllegalStateException(
                    "A union state replay starts from stale endpoint class state");
        }
        TypedSymmetryGroup merged =
                TypedSlottedPortEGraph.mergeStabilizingSymmetries(
                        child, parent, edge);

        for (CertificateTracePayload.ShapeRehome rehome : union.rehomes()) {
            boolean wasDirty = expectedDirty.remove(rehome.original().key());
            removeExact(expectedRecords, rehome.original());
            putFresh(expectedRecords, rehome.replacement());
            if (wasDirty) {
                expectedDirty.add(rehome.replacement().key());
            }
        }
        for (RetiredShapeRecordCertificate retirement : union.retirements()) {
            expectedDirty.remove(retirement.retiredRecord());
            removeExact(expectedRecords, retirement.retiredShapeRecord());
            requireRetirementTarget(expectedRecords, retirement);
        }

        expectedClasses.put(
                parent.id(), parent.withSymmetryGroup(merged));
        expectedClasses = replaceClassMapContents(
                expectedClasses,
                recordsWithCurrentClassState(expectedClasses, expectedRecords));
        expectedParents.put(
                child.id(), ParentAssignment.direct(ParentStep.certified(edge)));

        Map<EClassId, Set<ParentRecordKey>> uses =
                parentUsesFromRecords(expectedRecords);
        expectedDirty.addAll(uses.getOrDefault(
                child.id(), Collections.emptySet()));
        if (!merged.equals(parent.symmetryGroup())) {
            expectedDirty.addAll(uses.getOrDefault(
                    parent.id(), Collections.emptySet()));
        }
    }

    private static Map<EClassId, TypedEClassRecord> replaceClassMapContents(
            Map<EClassId, TypedEClassRecord> target,
            Map<EClassId, TypedEClassRecord> replacement) {
        target.clear();
        target.putAll(replacement);
        return target;
    }

    private static Map<EClassId, TypedEClassRecord> recordsWithCurrentClassState(
            Map<EClassId, TypedEClassRecord> state,
            Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> records) {
        Map<EClassId, Map<CanonicalShape, ShapeWitness>> witnesses =
                new LinkedHashMap<>();
        for (CertificateTracePayload.ShapeRecord record : records.values()) {
            TypedEClassRecord owner = state.get(record.key().owner());
            if (owner == null || !owner.interfaceView().equals(record.owner())) {
                throw new IllegalStateException(
                        "A replayed shape record uses absent or stale class metadata");
            }
            ShapeWitness prior = witnesses
                    .computeIfAbsent(record.key().owner(), ignored -> new TreeMap<>())
                    .put(record.key().shape(), record.witness());
            if (prior != null && !prior.equals(record.witness())) {
                throw new IllegalStateException(
                        "A replayed class has conflicting witnesses for one shape");
            }
        }
        Map<EClassId, TypedEClassRecord> result = new LinkedHashMap<>();
        for (Map.Entry<EClassId, TypedEClassRecord> entry : state.entrySet()) {
            TypedEClassRecord record = entry.getValue();
            result.put(
                    entry.getKey(),
                    TypedEClassRecord.of(
                            record.interfaceView(),
                            witnesses.getOrDefault(
                                    entry.getKey(), Collections.emptyMap()),
                            record.symmetryGroup()));
        }
        return result;
    }

    private static Map<EClassId, Set<ParentRecordKey>> parentUsesFromRecords(
            Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> records) {
        Map<EClassId, Set<ParentRecordKey>> uses = new LinkedHashMap<>();
        for (ParentRecordKey key : records.keySet()) {
            Set<EClassId> children = new LinkedHashSet<>();
            collectInvocationIds(key.shape().node(), children);
            for (EClassId child : children) {
                uses.computeIfAbsent(child, ignored -> new LinkedHashSet<>()).add(key);
            }
        }
        return uses;
    }

    private static void compressAll(
            Map<EClassId, ParentAssignment> assignments) {
        for (EClassId id : new TreeSet<>(assignments.keySet())) {
            ParentPath path = pathToRoot(
                    id, assignments, new LinkedHashSet<>());
            assignments.put(id, path.isIdentity()
                    ? ParentAssignment.root(path.start())
                    : ParentAssignment.compressed(path));
        }
    }

    private static ParentPath pathToRoot(
            EClassId id,
            Map<EClassId, ParentAssignment> assignments,
            Set<EClassId> visiting) {
        if (!visiting.add(id)) {
            throw new IllegalStateException(
                    "Trace parent assignments contain a cycle");
        }
        ParentAssignment assignment = assignments.get(id);
        if (assignment == null) {
            throw new IllegalStateException(
                    "Trace parent assignments reference an absent e-class");
        }
        ParentPath result = assignment.isRoot()
                ? ParentPath.identity(assignment.child())
                : assignment.provenancePath().andThen(pathToRoot(
                        assignment.parentInvocation().eclass().id(),
                        assignments,
                        visiting));
        visiting.remove(id);
        return result;
    }

    private void verifyRebuildStart(
            CertificateTraceSnapshot after,
            CertificateTracePayload.RebuildStart payload) {
        if (status != GraphStatus.DIRTY
                || !payload.initialStateKey().equals(stateKey)
                || !stateKey.equals(after.stateKey)) {
            throw new IllegalStateException(
                    "Rebuild start is not an exact dirty-state boundary");
        }
    }

    private void verifySymmetryTransition(
            CertificateTraceSnapshot after,
            CertificateTracePayload.Symmetry payload) {
        TypedEClassRecord original = classes.get(payload.eclass());
        if (original == null
                || !original.interfaceView().equals(payload.certificate().eclass())) {
            throw new IllegalStateException(
                    "Symmetry transition starts from stale class metadata");
        }
        Map<EClassId, TypedEClassRecord> expectedClasses =
                new LinkedHashMap<>(classes);
        expectedClasses.put(
                payload.eclass(),
                original.withSymmetryGroup(
                        original.symmetryGroup().withCertifiedGenerator(
                                original.interfaceView(), payload.certificate())));
        Set<ParentRecordKey> expectedDirty = new LinkedHashSet<>(dirtyParents);
        expectedDirty.addAll(parentUses.getOrDefault(
                payload.eclass(), Collections.emptySet()));
        if (!expectedClasses.equals(after.classes)
                || !parents.equals(after.parents)
                || !hashCons.equals(after.hashCons)
                || !shapeCertificates.equals(after.shapeCertificates)
                || !retiredShapeRecords.equals(after.retiredShapeRecords)
                || !parentUses.equals(after.parentUses)
                || !expectedDirty.equals(after.dirtyParents)
                || !restrictions.equals(after.restrictions)
                || !insertions.equals(after.insertions)
                || after.status != GraphStatus.DIRTY
                || after.revision != Math.incrementExact(revision)) {
            throw new IllegalStateException(
                    "Symmetry transition has an unexplained state effect");
        }
    }

    private void verifyPathCompressionTransition(
            CertificateTraceSnapshot after,
            CertificateTracePayload.PathCompression payload) {
        Map<EClassId, ParentAssignment> expected = new LinkedHashMap<>(parents);
        EClassId start = payload.result().normalizedInvocation().eclass().id();
        ParentPath path = compressPath(start, expected, new LinkedHashSet<>());
        if (!path.equals(payload.result().parentPath())
                || !expected.equals(after.parents)
                || !classes.equals(after.classes)
                || !hashCons.equals(after.hashCons)
                || !shapeCertificates.equals(after.shapeCertificates)
                || !retiredShapeRecords.equals(after.retiredShapeRecords)
                || !parentUses.equals(after.parentUses)
                || !dirtyParents.equals(after.dirtyParents)
                || !restrictions.equals(after.restrictions)
                || !insertions.equals(after.insertions)
                || status != after.status
                || revision != after.revision) {
            throw new IllegalStateException(
                    "Path-compression event is not the exact parent-path update");
        }
    }

    private ParentPath compressPath(
            EClassId id,
            Map<EClassId, ParentAssignment> expected,
            Set<EClassId> visiting) {
        if (!visiting.add(id)) {
            throw new IllegalStateException(
                    "Trace parent assignments contain a cycle");
        }
        ParentAssignment current = parents.get(id);
        if (current == null) {
            throw new IllegalStateException(
                    "Path compression starts from an absent e-class");
        }
        ParentPath result;
        if (current.isRoot()) {
            result = ParentPath.identity(current.child());
        } else {
            ParentPath tail = compressPath(
                    current.parentInvocation().eclass().id(), expected, visiting);
            result = current.provenancePath().andThen(tail);
            expected.put(id, ParentAssignment.compressed(result));
        }
        visiting.remove(id);
        return result;
    }

    private void verifyRebuildCompletion(
            CertificateTraceSnapshot after,
            CertificateTracePayload.RebuildComplete payload) {
        RebuildReport report = payload.report();
        boolean changed = report.changedKeys() != 0
                || report.collisions() != 0
                || report.certifiedUnions() != 0;
        if (!classes.equals(after.classes)
                || !parents.equals(after.parents)
                || !hashCons.equals(after.hashCons)
                || !shapeCertificates.equals(after.shapeCertificates)
                || !retiredShapeRecords.equals(after.retiredShapeRecords)
                || !parentUses.equals(after.parentUses)
                || !dirtyParents.isEmpty()
                || !after.dirtyParents.isEmpty()
                || !restrictions.equals(after.restrictions)
                || !insertions.equals(after.insertions)
                || status != GraphStatus.DIRTY
                || after.status != GraphStatus.QUIESCENT
                || after.revision != (changed
                        ? Math.incrementExact(revision) : revision)) {
            throw new IllegalStateException(
                    "Rebuild completion is not the exact quiescence transition");
        }
    }

    private void verifyRestrictionTransition(
            CertificateTraceSnapshot after,
            InterfaceRestrictionCertificate restriction) {
        EClassId restricted = restriction.originalInterface().id();
        TypedEClassRecord original = classes.get(restricted);
        if (original == null
                || !original.interfaceView().equals(restriction.originalInterface())) {
            throw new IllegalStateException(
                    "Interface restriction does not start from the exact pre-state owner");
        }
        Map<EClassId, TypedEClassRecord> expectedClasses =
                new LinkedHashMap<>(classes);
        TypedSymmetryGroup restrictedGroup =
                TypedSlottedPortEGraph.restrictSymmetryGroup(
                        original, restriction);
        expectedClasses.put(
                restricted,
                original.withInterfaceAndState(
                        restriction.restrictedInterface(),
                        restriction.transportedShapeWitnesses(),
                        restrictedGroup));
        if (!expectedClasses.equals(after.classes)) {
            throw new IllegalStateException(
                    "Interface restriction changed unrelated class state");
        }

        Map<EClassId, ParentAssignment> expectedParents =
                restrictedParents(restriction);
        if (!expectedParents.equals(after.parents)) {
            throw new IllegalStateException(
                    "Interface restriction parent transport is not exact");
        }
        if (!hashCons.equals(after.hashCons)
                || !parentUses.equals(after.parentUses)
                || !retiredShapeRecords.equals(after.retiredShapeRecords)
                || !insertions.equals(after.insertions)) {
            throw new IllegalStateException(
                    "Interface restriction mutated an unrelated state section");
        }

        Map<EClassId, List<InterfaceRestrictionCertificate>> expectedRestrictions =
                new LinkedHashMap<>();
        for (Map.Entry<EClassId, List<InterfaceRestrictionCertificate>> entry
                : restrictions.entrySet()) {
            expectedRestrictions.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        expectedRestrictions.computeIfAbsent(
                restricted, ignored -> new ArrayList<>()).add(restriction);
        if (!expectedRestrictions.equals(after.restrictions)) {
            throw new IllegalStateException(
                    "Interface restriction history is not append-only and exact");
        }

        Set<ParentRecordKey> expectedDirty = new LinkedHashSet<>(dirtyParents);
        expectedDirty.addAll(parentUses.getOrDefault(
                restricted, Collections.emptySet()));
        if (!expectedDirty.equals(after.dirtyParents)
                || after.status != GraphStatus.DIRTY
                || after.revision != Math.incrementExact(revision)) {
            throw new IllegalStateException(
                    "Interface restriction has inconsistent dirty/status/revision effects");
        }
    }

    private Map<EClassId, ParentAssignment> restrictedParents(
            InterfaceRestrictionCertificate restriction) {
        Map<EClassId, ParentAssignment> expected = new LinkedHashMap<>(parents);
        EClassId restricted = restriction.originalInterface().id();
        for (EClassId candidate : parents.keySet()) {
            ParentPath path = fullPath(candidate, new LinkedHashSet<>());
            if (!path.end().id().equals(restricted)) {
                continue;
            }
            if (candidate.equals(restricted)) {
                expected.put(candidate, ParentAssignment.root(
                        restriction.restrictedInterface()));
                continue;
            }
            TypedEClassInterface child = path.start();
            TypedEmbedding oldLeaderInChild = path.compositeEmbedding();
            TypedEmbedding newLeaderInChild = restriction.inclusion()
                    .andThen(oldLeaderInChild);
            TypedEqualityCertificate factorization = EqualityCertificates.rename(
                    restriction.factorization(), oldLeaderInChild);
            TypedEqualityCertificate derivation = EqualityCertificates.transitive(
                    path.composedCertificate(), factorization);
            ParentEdgeCertificate edge = new ParentEdgeCertificate(
                    child,
                    new TypedInvocation(
                            restriction.restrictedInterface(), newLeaderInChild),
                    derivation);
            expected.put(candidate, ParentAssignment.direct(
                    ParentStep.certified(edge)));
        }
        return expected;
    }

    private ParentPath fullPath(EClassId id, Set<EClassId> visiting) {
        if (!visiting.add(id)) {
            throw new IllegalStateException(
                    "Trace parent assignments contain a cycle");
        }
        ParentAssignment assignment = parents.get(id);
        if (assignment == null) {
            throw new IllegalStateException(
                    "Trace parent path references an absent e-class");
        }
        ParentPath result = assignment.isRoot()
                ? ParentPath.identity(assignment.child())
                : assignment.provenancePath().andThen(fullPath(
                        assignment.parentInvocation().eclass().id(), visiting));
        visiting.remove(id);
        return result;
    }

    private void verifyClassDomain(
            CertificateTraceSnapshot after,
            CertificateTracePayload payload) {
        Set<EClassId> expected = new TreeSet<>(classes.keySet());
        if (payload instanceof CertificateTracePayload.Insertion) {
            CertifiedInsertionResult insertion =
                    ((CertificateTracePayload.Insertion) payload).result();
            if (classes.containsKey(insertion.insertedClass().id())) {
                throw new IllegalStateException(
                        "An insertion payload reuses an existing e-class id");
            }
            expected.add(insertion.insertedClass().id());
        }
        if (!expected.equals(after.classes.keySet())) {
            throw new IllegalStateException(
                    "Trace transition has an unexplained e-class domain change");
        }
    }

    private void verifyUnionTopology(
            CertificateTraceSnapshot after,
            List<CertificateTracePayload.Union> unions,
            CertificateTracePayload payload) {
        Map<EClassId, EClassId> simulated = directParents(parents);
        for (EClassId added : after.classes.keySet()) {
            simulated.putIfAbsent(added, added);
        }
        Set<StructuralKey> expectedNewEdges = new LinkedHashSet<>();
        for (CertificateTracePayload.Union union : unions) {
            ParentEdgeCertificate edge = union.certificate();
            TypedEClassRecord child = after.classes.get(edge.child().id());
            TypedEClassRecord parent = after.classes.get(edge.parent().id());
            if (child == null || parent == null
                    || !child.interfaceView().equals(edge.child())
                    || !parent.interfaceView().equals(edge.parent())) {
                throw new IllegalStateException(
                        "A retained union uses stale or absent endpoint interfaces");
            }
            EClassId childRoot = rootOf(edge.child().id(), simulated);
            EClassId parentRoot = rootOf(edge.parent().id(), simulated);
            if (!childRoot.equals(edge.child().id())
                    || !parentRoot.equals(edge.parent().id())
                    || childRoot.equals(parentRoot)) {
                throw new IllegalStateException(
                        "Retained unions are not the exact ordered leader transitions");
            }
            simulated.put(edge.child().id(), edge.parent().id());
            StructuralKey key = ParentStep.certified(edge).structuralKey();
            if (!expectedNewEdges.add(key)) {
                throw new IllegalStateException(
                        "A retained union transition is duplicated");
            }
        }
        Map<EClassId, EClassId> observed = directParents(after.parents);
        for (EClassId id : after.classes.keySet()) {
            if (!rootOf(id, simulated).equals(rootOf(id, observed))) {
                throw new IllegalStateException(
                        "Retained unions do not explain the exact parent topology");
            }
        }

        if (!(payload instanceof CertificateTracePayload.Restriction)) {
            Set<StructuralKey> beforeEdges = primitiveEdgeKeys(parents);
            Set<StructuralKey> afterEdges = primitiveEdgeKeys(after.parents);
            Set<StructuralKey> introduced = new LinkedHashSet<>(afterEdges);
            introduced.removeAll(beforeEdges);
            if (!introduced.equals(expectedNewEdges)) {
                throw new IllegalStateException(
                        "Retained unions do not equal the installed primitive parent edges");
            }
        }
    }

    private void verifyRetirementLedger(
            CertificateTraceSnapshot after,
            CertificateTracePayload payload,
            List<CertificateTracePayload.Union> unions) {
        Map<ParentRecordKey, RetiredShapeRecordCertificate> expected =
                new LinkedHashMap<>(retiredShapeRecords);
        if (payload instanceof CertificateTracePayload.RebuildRecord) {
            RetiredShapeRecordCertificate retirement =
                    ((CertificateTracePayload.RebuildRecord) payload).retirement();
            if (retirement != null) {
                putRetirement(expected, retirement);
            }
        }
        for (CertificateTracePayload.Union union : unions) {
            for (RetiredShapeRecordCertificate retirement : union.retirements()) {
                putRetirement(expected, retirement);
            }
        }
        if (!expected.equals(after.retiredShapeRecords)) {
            throw new IllegalStateException(
                    "Trace payload does not conserve the append-only retirement ledger");
        }
    }

    private static void putRetirement(
            Map<ParentRecordKey, RetiredShapeRecordCertificate> target,
            RetiredShapeRecordCertificate retirement) {
        RetiredShapeRecordCertificate prior = target.putIfAbsent(
                retirement.retiredRecord(), retirement);
        if (prior != null && !prior.equals(retirement)) {
            throw new IllegalStateException(
                    "One retired shape has two transition certificates");
        }
    }

    private static List<CertificateTracePayload.Union> unions(
            CertificateTracePayload payload) {
        if (payload instanceof CertificateTracePayload.Union) {
            return List.of((CertificateTracePayload.Union) payload);
        }
        if (payload instanceof CertificateTracePayload.Insertion) {
            return ((CertificateTracePayload.Insertion) payload)
                    .generatedSubtransitions();
        }
        if (payload instanceof CertificateTracePayload.RebuildRecord) {
            return ((CertificateTracePayload.RebuildRecord) payload)
                    .generatedSubtransitions();
        }
        return Collections.emptyList();
    }

    private static Map<EClassId, EClassId> directParents(
            Map<EClassId, ParentAssignment> assignments) {
        Map<EClassId, EClassId> result = new LinkedHashMap<>();
        for (Map.Entry<EClassId, ParentAssignment> entry : assignments.entrySet()) {
            result.put(entry.getKey(), entry.getValue().parentInvocation().eclass().id());
        }
        return result;
    }

    private static EClassId rootOf(
            EClassId start,
            Map<EClassId, EClassId> parents) {
        Set<EClassId> seen = new LinkedHashSet<>();
        EClassId current = start;
        while (true) {
            if (!seen.add(current)) {
                throw new IllegalStateException(
                        "Trace parent topology contains a cycle");
            }
            EClassId parent = parents.get(current);
            if (parent == null) {
                throw new IllegalStateException(
                        "Trace parent topology references an absent e-class");
            }
            if (parent.equals(current)) {
                return current;
            }
            current = parent;
        }
    }

    private static Set<StructuralKey> primitiveEdgeKeys(
            Map<EClassId, ParentAssignment> assignments) {
        Set<StructuralKey> result = new LinkedHashSet<>();
        for (ParentAssignment assignment : assignments.values()) {
            for (ParentStep step : assignment.provenancePath().steps()) {
                if (!step.hasCertificate()) {
                    throw new IllegalStateException(
                            "A producer trace contains an uncertified primitive parent step");
                }
                result.add(step.structuralKey());
            }
        }
        return result;
    }

    private static void applyUnions(
            Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> replay,
            List<CertificateTracePayload.Union> unions) {
        for (CertificateTracePayload.Union union : unions) {
            applyUnion(replay, union);
        }
    }

    private static void applyUnion(
            Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> replay,
            CertificateTracePayload.Union union) {
        for (CertificateTracePayload.ShapeRehome rehome : union.rehomes()) {
            removeExact(replay, rehome.original());
            putFresh(replay, rehome.replacement());
        }
        for (RetiredShapeRecordCertificate retirement : union.retirements()) {
            removeExact(replay, retirement.retiredShapeRecord());
            requireRetirementTarget(replay, retirement);
        }
    }

    private static void putFresh(
            Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> replay,
            CertificateTracePayload.ShapeRecord record) {
        CertificateTracePayload.ShapeRecord prior = replay.putIfAbsent(
                record.key(), record);
        if (prior != null) {
            throw new IllegalStateException(
                    "Trace transition overwrites a live shape without retirement");
        }
    }

    private static void removeExact(
            Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> replay,
            CertificateTracePayload.ShapeRecord expected) {
        CertificateTracePayload.ShapeRecord removed = replay.remove(expected.key());
        if (!expected.equals(removed)) {
            throw new IllegalStateException(
                    "Trace transition does not bind the exact old live shape record");
        }
    }

    private static void requireRetirementTarget(
            Map<ParentRecordKey, CertificateTracePayload.ShapeRecord> replay,
            RetiredShapeRecordCertificate retirement) {
        retirement.verify();
        CertificateTracePayload.ShapeRecord live = replay.get(
                retirement.retainedRecord());
        if (!retirement.retainedShapeRecord().equals(live)) {
            throw new IllegalStateException(
                    "Retirement does not point to its exact live duplicate");
        }
    }
}
