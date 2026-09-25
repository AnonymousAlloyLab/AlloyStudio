package is.fivefivefive.CanDis.theory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Graph-owned typed state {@code G=(U,M,H)} with certified Phase G rebuilding. */
public final class TypedSlottedPortEGraph {
    private static final String ENGINE_IDENTIFIER = "typed-slotted-port-egraph";
    private final NavigableMap<EClassId, TypedEClassRecord> classes = new TreeMap<>();
    private final TypedRenamedUnionFind unionFind = new TypedRenamedUnionFind();
    private final NavigableMap<CanonicalShape, NavigableSet<EClassId>> hashCons =
            new TreeMap<>();
    private final NavigableMap<CollisionPairKey, Long> incompatibleCollisions =
            new TreeMap<>();
    private final NavigableMap<ParentRecordKey, TypedEqualityCertificate>
            shapeCertificates = new TreeMap<>();
    private final NavigableMap<ParentRecordKey, RetiredShapeRecordCertificate>
            retiredShapeRecords = new TreeMap<>();
    private final NavigableMap<EClassId, NavigableSet<ParentRecordKey>>
            parentUses = new TreeMap<>();
    private final NavigableSet<ParentRecordKey> dirtyParents = new TreeSet<>();
    private final NavigableMap<EClassId, List<InterfaceRestrictionCertificate>>
            restrictionHistory = new TreeMap<>();
    private final NavigableMap<EClassId, CertifiedInsertionResult>
            insertionHistory = new TreeMap<>();
    private final GraphCertificateMode certificateMode;
    private final SemanticProfile semanticProfile;
    private final CertificateTraceSink traceSink;
    private GraphStatus status = GraphStatus.QUIESCENT;
    private long coherenceRevision;
    private long collisionOrientationAttempts;
    private long hashIndexRebuilds;
    private long traceSequence;
    private boolean rebuildActive;
    private ParentRecordKey rebuildingRecord;

    public TypedSlottedPortEGraph() {
        this(
                GraphCertificateMode.REQUIRED,
                SemanticProfile.alloyOverflowForbidding(),
                NoOpCertificateTraceSink.instance());
    }

    /** Explicit proof-retaining construction; ordinary callers use the no-op default. */
    public TypedSlottedPortEGraph(CertificateTraceSink traceSink) {
        this(
                GraphCertificateMode.REQUIRED,
                SemanticProfile.alloyOverflowForbidding(),
                traceSink);
    }

    public TypedSlottedPortEGraph(
            SemanticProfile semanticProfile,
            CertificateTraceSink traceSink) {
        this(GraphCertificateMode.REQUIRED, semanticProfile, traceSink);
    }

    private TypedSlottedPortEGraph(
            GraphCertificateMode certificateMode,
            SemanticProfile semanticProfile,
            CertificateTraceSink traceSink) {
        this.certificateMode = Objects.requireNonNull(certificateMode, "certificateMode");
        this.semanticProfile = semanticProfile;
        if (certificateMode == GraphCertificateMode.REQUIRED) {
            Objects.requireNonNull(semanticProfile, "semanticProfile");
            if (!semanticProfile.isAdmissibleAlloyProfile()) {
                throw new IllegalArgumentException(
                        "A strict graph requires an admitted Alloy semantic profile");
            }
        } else if (semanticProfile != null) {
            throw new IllegalArgumentException(
                    "A structural fixture must not claim production profile authority");
        }
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink");
    }

    static TypedSlottedPortEGraph structuralFixture() {
        return new TypedSlottedPortEGraph(
                GraphCertificateMode.STRUCTURAL_FIXTURE,
                null,
                NoOpCertificateTraceSink.instance());
    }

    static TypedSlottedPortEGraph certifiedFixture() {
        return new TypedSlottedPortEGraph(
                GraphCertificateMode.TEST_ONLY,
                null,
                NoOpCertificateTraceSink.instance());
    }

    public String engineIdentifier() {
        return ENGINE_IDENTIFIER;
    }

    public String canonicalizerVersion() {
        return ProductionGraphCanonicalizer.VERSION;
    }

    public String leaderKernelVersion() {
        return LeaderKernelExtractor.VERSION;
    }

    public String certificateVersion() {
        return CertificateVerifier.VERSION;
    }

    public String rebuildVersion() {
        return "typed-fixed-batch-rebuild-v1";
    }

    public GraphCertificateMode certificateMode() {
        return certificateMode;
    }

    public SemanticProfile semanticProfile() {
        if (semanticProfile == null) {
            throw new IllegalStateException(
                    "A structural fixture has no production semantic profile");
        }
        return semanticProfile;
    }

    /**
     * Phase D setup primitive retained only for structural gate fixtures; the
     * strict graph uses the certified insertion path below.
     */
    synchronized void registerRecordForPhaseD(TypedEClassRecord record) {
        requireStructuralFixture("Phase D record registration");
        registerRecord(record);
    }

    /** Empty-class setup for exercising the Phase F transition boundary before insertion. */
    synchronized void registerEmptyClassForPhaseF(TypedEClassInterface eclass) {
        if (!requiresCertificates()) {
            throw new IllegalStateException(
                    "Phase F setup requires certificate-enforcing graph mode");
        }
        registerRecord(TypedEClassRecord.empty(
                Objects.requireNonNull(eclass, "eclass")));
        coherenceRevision = Math.incrementExact(coherenceRevision);
    }

    /**
     * Admits one already-flat record into a fixed batch with an exact equation
     * for every stored shape. This remains the fixed-batch counterpart of the
     * source-level insertion wrapper for witness-dependent {@code d_n^w}.
     */
    synchronized void admitFixedBatchRecordCertified(
            TypedEClassRecord record,
            Map<CanonicalShape, ? extends TypedEqualityCertificate> certificates) {
        admitFixedBatchRecordCertified(
                record, certificates, Collections.emptyMap());
    }

    synchronized void admitFixedBatchRecordCertified(
            TypedEClassRecord record,
            Map<CanonicalShape, ? extends TypedEqualityCertificate> certificates,
            Map<CanonicalShape, ? extends TypedEqualityCertificate> constructions) {
        if (!requiresCertificates()) {
            throw new IllegalStateException(
                    "Certified fixed-batch admission requires strict graph mode");
        }
        TypedEClassRecord checkedRecord = Objects.requireNonNull(record, "record");
        Objects.requireNonNull(certificates, "certificates");
        Objects.requireNonNull(constructions, "constructions");
        if (status != GraphStatus.QUIESCENT) {
            throw new IllegalStateException(
                    "A fixed-batch record can be admitted only between rebuild epochs");
        }
        if (!checkedRecord.shapeWitnesses().keySet().equals(certificates.keySet())) {
            throw new IllegalArgumentException(
                    "Certified admission requires exactly one equation per stored shape");
        }
        java.util.Set<CanonicalShape> requiredConstructions = new java.util.TreeSet<>();
        if (certificateMode == GraphCertificateMode.REQUIRED) {
            for (CanonicalShape shape : checkedRecord.shapeWitnesses().keySet()) {
                if (!shape.node().operator().containerLaws().isEmpty()) {
                    requiredConstructions.add(shape);
                }
            }
        }
        if (!requiredConstructions.equals(constructions.keySet())) {
            throw new IllegalArgumentException(
                    "Fixed-batch source constructions must exactly cover law-bearing shapes");
        }
        checkedRecord.symmetryGroup().requireCertifiedFor(
                checkedRecord.interfaceView());
        validateStoredInvocations(checkedRecord);
        for (CanonicalShape shape : checkedRecord.shapeWitnesses().keySet()) {
            requireCertifiedNodeTheory(shape.node());
            TypedEqualityCertificate construction = constructions.get(shape);
            if (construction == null) {
                requireConcreteConstruction(shape.node(), null);
            } else {
                TypedENode constructionTarget = concreteConstructionTarget(construction);
                requireCertifiedNodeTheory(constructionTarget);
                requireConcreteConstruction(constructionTarget, construction);
                if (!canonicalizeWithConstructionReadOnly(
                        constructionTarget, construction)
                        .shape().equals(shape)) {
                    throw new IllegalArgumentException(
                            "Fixed-batch construction normalizes to another shape");
                }
            }
            for (EClassId child : invocationIds(shape.node())) {
                if (!unionFind.isLeader(child)) {
                    throw new IllegalArgumentException(
                            "Fixed-batch admission is bottom-up and requires leader children");
                }
            }
            EffectiveShapeCollisionCertificate.orientShapeEquation(
                    shape,
                    checkedRecord,
                    checkedRecord.shapeWitnesses().get(shape),
                    certificates.get(shape));
        }
        if (classes.containsKey(checkedRecord.id())) {
            throw new IllegalArgumentException(
                    "Duplicate e-class id: " + checkedRecord.id());
        }

        unionFind.register(checkedRecord.interfaceView());
        classes.put(checkedRecord.id(), checkedRecord);
        for (CanonicalShape shape : checkedRecord.shapeWitnesses().keySet()) {
            ParentRecordKey key = new ParentRecordKey(checkedRecord.id(), shape);
            shapeCertificates.put(key, EffectiveShapeCollisionCertificate.orientShapeEquation(
                    shape,
                    checkedRecord,
                    checkedRecord.shapeWitnesses().get(shape),
                    certificates.get(shape)));
            addHashOwner(shape, checkedRecord.id());
            indexRecord(key);
        }
        for (CanonicalShape shape : checkedRecord.shapeWitnesses().keySet()) {
            resolveShapeCollisions(shape);
        }
        coherenceRevision = Math.incrementExact(coherenceRevision);
        checkInvariants();
    }

    private void registerRecord(TypedEClassRecord record) {
        Objects.requireNonNull(record, "record");
        if (status != GraphStatus.QUIESCENT) {
            throw new IllegalStateException("Cannot register a setup record in a dirty graph");
        }
        if (classes.containsKey(record.id())) {
            throw new IllegalArgumentException("Duplicate e-class id: " + record.id());
        }
        validateStoredInvocations(record);
        unionFind.register(record.interfaceView());
        classes.put(record.id(), record);
        for (CanonicalShape shape : record.shapeWitnesses().keySet()) {
            addHashOwner(shape, record.id());
            indexRecord(new ParentRecordKey(record.id(), shape));
        }
        checkInvariants();
    }

    /** Package-private raw link used only to discharge the Phase D algebra gate. */
    synchronized void linkLeadersForPhaseD(ParentStep step) {
        requireStructuralFixture("Phase D leader linking");
        Objects.requireNonNull(step, "step");
        requireRecord(step.child());
        requireRecord(step.parent());
        unionFind.linkRoots(step);
        status = GraphStatus.DIRTY;
        checkInvariants();
    }

    /** Installs exactly one distinct-leader parent edge after checking its proof. */
    public synchronized ParentStep unionCertified(ParentEdgeCertificate certificate) {
        ParentEdgeCertificate checked = Objects.requireNonNull(
                certificate, "certificate");
        CertificateVerifier.verifyParentEdge(checked);
        requireRecord(checked.child());
        requireRecord(checked.parent());
        if (checked.child().id().equals(checked.parent().id())) {
            throw new IllegalArgumentException(
                    "A same-leader equation is not a union or an automatic symmetry");
        }
        if (!unionFind.isLeader(checked.child().id())
                || !unionFind.isLeader(checked.parent().id())) {
            throw new IllegalArgumentException(
                    "Certified union endpoints must be current distinct leaders");
        }
        CertificateTraceSnapshot before = traceSnapshot();
        UnionMutation mutation = unionCertifiedInternal(checked);
        coherenceRevision = Math.incrementExact(coherenceRevision);
        checkInvariants();
        appendTrace(
                CertificateTraceEvent.Kind.UNION,
                before,
                mutation.trace.withRevisionIncrement());
        return mutation.step;
    }

    /** Adds one separately certified generator; endpoint equality alone is insufficient. */
    public synchronized boolean addSymmetryCertified(
            EClassId leaderId,
            SymmetryCertificate certificate) {
        TypedEClassRecord record = eclass(Objects.requireNonNull(leaderId, "leaderId"));
        SymmetryCertificate checked = Objects.requireNonNull(certificate, "certificate");
        CertificateVerifier.verifySymmetry(checked);
        if (!unionFind.isLeader(leaderId)) {
            throw new IllegalArgumentException(
                    "Only a current leader may receive a symmetry generator");
        }
        if (!record.interfaceView().equals(checked.eclass())) {
            throw new IllegalArgumentException(
                    "Symmetry certificate names a different graph e-class");
        }
        TypedSymmetryGroup current = record.symmetryGroup();
        current.requireCertifiedFor(record.interfaceView());
        if (current.contains(checked.inducedPermutation())) {
            return false;
        }
        CertificateTraceSnapshot before = traceSnapshot();
        TypedSymmetryGroup updated = current.withCertifiedGenerator(
                record.interfaceView(), checked);
        classes.put(leaderId, record.withSymmetryGroup(updated));
        markParentsDirty(leaderId);
        status = GraphStatus.DIRTY;
        coherenceRevision = Math.incrementExact(coherenceRevision);
        checkInvariants();
        appendTrace(
                CertificateTraceEvent.Kind.ADD_SYMMETRY,
                before,
                new CertificateTracePayload.Symmetry(leaderId, checked));
        return true;
    }

    /** Checks restriction provenance without performing the Phase G transport transaction. */
    public synchronized void verifyInterfaceRestriction(
            InterfaceRestrictionCertificate certificate) {
        InterfaceRestrictionCertificate checked = Objects.requireNonNull(
                certificate, "certificate");
        CertificateVerifier.verifyInterfaceRestriction(checked);
        TypedEClassRecord record = eclass(checked.originalInterface().id());
        if (!record.interfaceView().equals(checked.originalInterface())) {
            throw new IllegalArgumentException(
                    "Restriction certificate uses stale e-class metadata");
        }
        if (!record.shapeWitnesses().keySet().equals(
                    checked.transportedShapeWitnesses().keySet())) {
            throw new IllegalArgumentException(
                    "Restriction certificate does not transport every stored shape");
        }
        for (Map.Entry<CanonicalShape, ShapeWitness> entry
                : record.shapeWitnesses().entrySet()) {
            ShapeWitness original = entry.getValue();
            ShapeWitness transported = checked.transportedShapeWitnesses().get(
                    entry.getKey());
            if (transported == null
                    || !transported.exactSlots().equals(original.exactSlots())
                    || !transported.ambientSupport().equals(original.ambientSupport())
                    || !transported.instantiatingRenaming().equals(
                            original.instantiatingRenaming())
                    || !transported.exposedInterface().equals(
                            checked.restrictedInterface().exposedSlots())) {
                throw new IllegalArgumentException(
                        "Restriction certificate changes a stored shape witness");
            }
        }
    }

    /** Performs the sole atomic interface-narrowing transition. */
    public synchronized void restrictInterfaceCertified(
            InterfaceRestrictionCertificate certificate) {
        if (!requiresCertificates()) {
            throw new IllegalStateException(
                    "Interface restriction requires strict certificate mode");
        }
        InterfaceRestrictionCertificate checked = Objects.requireNonNull(
                certificate, "certificate");
        verifyInterfaceRestriction(checked);
        TypedEClassRecord originalRecord = eclass(checked.originalInterface().id());
        if (!unionFind.isLeader(originalRecord.id())) {
            throw new IllegalArgumentException(
                    "Only a current leader interface may be restricted");
        }
        CertificateTraceSnapshot before = traceSnapshot();

        TypedSymmetryGroup restrictedGroup = restrictSymmetryGroup(
                originalRecord, checked);
        NavigableMap<CanonicalShape, ShapeWitness> transportedWitnesses =
                new TreeMap<>(checked.transportedShapeWitnesses());
        NavigableMap<ParentRecordKey, TypedEqualityCertificate> transportedEquations =
                new TreeMap<>();
        for (Map.Entry<CanonicalShape, ShapeWitness> entry
                : originalRecord.shapeWitnesses().entrySet()) {
            ParentRecordKey key = new ParentRecordKey(originalRecord.id(), entry.getKey());
            TypedEqualityCertificate oldEquation = requireShapeCertificate(key);
            TypedEmbedding oldInterfaceInAmbient = TypedEmbedding.inclusion(
                    originalRecord.exposedSlots(), entry.getValue().ambientSupport());
            TypedEqualityCertificate factorization = EqualityCertificates.rename(
                    checked.factorization(), oldInterfaceInAmbient);
            transportedEquations.put(key, EqualityCertificates.transitive(
                    oldEquation, factorization));
        }

        unionFind.restrictLeader(checked);
        TypedEClassRecord replacement = originalRecord.withInterfaceAndState(
                checked.restrictedInterface(),
                transportedWitnesses,
                restrictedGroup);
        classes.put(replacement.id(), replacement);
        shapeCertificates.putAll(transportedEquations);
        restrictionHistory.computeIfAbsent(
                replacement.id(), ignored -> new ArrayList<>()).add(checked);
        markParentsDirty(replacement.id());
        status = GraphStatus.DIRTY;
        coherenceRevision = Math.incrementExact(coherenceRevision);
        checkInvariants();
        appendTrace(
                CertificateTraceEvent.Kind.RESTRICT_INTERFACE,
                before,
                new CertificateTracePayload.Restriction(checked));
    }

    public synchronized TypedFindResult findWithProvenance(TypedInvocation invocation) {
        CertificateTraceSnapshot before = traceSnapshot();
        TypedFindResult result = findNormalized(
                Objects.requireNonNull(invocation, "invocation"), true);
        checkInvariants();
        if (traceSink.enabled()
                && !before.stateKey().equals(traceSnapshot().stateKey())) {
            appendTrace(
                    CertificateTraceEvent.Kind.PATH_COMPRESSION,
                    before,
                    new CertificateTracePayload.PathCompression(result));
        }
        return result;
    }

    /** Canonicalizes one exact-support flat node against this quiescent graph. */
    public synchronized CanonicalizationResult canonicalize(TypedENode node) {
        return canonicalizeWithConstruction(
                Objects.requireNonNull(node, "node"), null);
    }

    public synchronized CanonicalizationResult canonicalizeConstructed(
            CertifiedFlatConstruction construction) {
        Objects.requireNonNull(construction, "construction");
        if (construction.collapsedToSingleton()) {
            throw new IllegalArgumentException(
                    "A smart-collapsed singleton has no operator node to canonicalize");
        }
        return canonicalizeWithConstruction(
                construction.node(), construction.certificate());
    }

    public synchronized CanonicalizationResult canonicalizeConstructed(
            CertifiedContainerConstruction construction) {
        Objects.requireNonNull(construction, "construction");
        return canonicalizeWithConstruction(
                construction.node(), construction.certificate());
    }

    private CanonicalizationResult canonicalizeWithConstruction(
            TypedENode node,
            TypedEqualityCertificate construction) {
        requireCertifiedNodeTheory(node);
        requireConcreteConstruction(node, construction);
        TypedENode exact = construction == null
                ? node : node.inExactSupportContext();
        return ProductionGraphCanonicalizer.instance().canonicalize(this, exact);
    }

    /** Validation-only canonicalization; rejected admissions must not compress paths. */
    private CanonicalizationResult canonicalizeWithConstructionReadOnly(
            TypedENode node,
            TypedEqualityCertificate construction) {
        requireCertifiedNodeTheory(node);
        requireConcreteConstruction(node, construction);
        TypedENode exact = construction == null
                ? node : node.inExactSupportContext();
        return ProductionGraphCanonicalizer.instance()
                .canonicalizeWithoutCompression(this, exact);
    }

    /** Replays an already stored/proved target without claiming a new source occurrence. */
    synchronized CanonicalizationResult canonicalizeStoredNode(TypedENode node) {
        requireCertifiedNodeTheory(Objects.requireNonNull(node, "node"));
        return ProductionGraphCanonicalizer.instance().canonicalize(this, node);
    }

    /** Captures the compact EC/PC/SC witness family of one quiescent prefix. */
    public synchronized CoherentWitnessFamily coherentWitnessFamily() {
        if (!requiresCertificates()) {
            throw new IllegalStateException(
                    "Coherent witnesses require the strict certificate graph");
        }
        requireQuiescent();
        checkInvariants();
        return CoherentWitnessFamily.capture(
                this,
                coherenceRevision,
                new LinkedHashMap<>(classes),
                unionFind.assignments(),
                new LinkedHashMap<>(shapeCertificates));
    }

    /** Opens a read-only bounded {@code Rep_G} oracle on one coherent prefix. */
    public synchronized BoundedFiniteUnfoldingOracle finiteUnfoldingOracle(
            CoherentWitnessFamily family,
            FiniteUnfoldingBounds bounds) {
        requireCurrentWitnessFamily(Objects.requireNonNull(family, "family"));
        return BoundedFiniteUnfoldingOracle.create(
                this, family, Objects.requireNonNull(bounds, "bounds"));
    }

    synchronized void requireCurrentWitnessFamily(CoherentWitnessFamily family) {
        if (!requiresCertificates()) {
            throw new IllegalStateException(
                    "Witness-dependent replay requires strict certificate mode");
        }
        requireQuiescent();
        Objects.requireNonNull(family, "family").requireCurrent(
                this, coherenceRevision);
    }

    synchronized void requireFreshWitnessDefinition(
            TypedEClassInterface fresh,
            KernelReplayCertificate replay) {
        requireCurrentWitnessFamily(Objects.requireNonNull(
                replay, "replay").witnessFamily());
        TypedEClassInterface checked = Objects.requireNonNull(fresh, "fresh");
        if (classes.containsKey(checked.id())) {
            throw new IllegalArgumentException(
                    "A witness definition requires an unregistered e-class id");
        }
        if (!checked.outputType().equals(replay.leaderKernel().kernel().outputType())
                || !checked.exposedSlots().equals(
                        replay.leaderKernel().effectiveSupport())) {
            throw new IllegalArgumentException(
                    "A fresh witness must use the effective kernel type and interface");
        }
    }

    /** Runs the structural canonicalizer and separately replays {@code xi} to {@code d^w}. */
    public synchronized CertifiedCanonicalizationResult canonicalizeCertified(
            TypedENode node,
            CoherentWitnessFamily family) {
        return canonicalizeCertifiedWithConstruction(node, family, null);
    }

    public synchronized CertifiedCanonicalizationResult
            canonicalizeCertifiedConstructed(
                    CertifiedFlatConstruction construction,
                    CoherentWitnessFamily family) {
        Objects.requireNonNull(construction, "construction");
        if (construction.collapsedToSingleton()) {
            throw new IllegalArgumentException(
                    "A smart-collapsed singleton has no operator node to canonicalize");
        }
        return canonicalizeCertifiedWithConstruction(
                construction.node(), family, construction.certificate());
    }

    public synchronized CertifiedCanonicalizationResult
            canonicalizeCertifiedConstructed(
                    CertifiedContainerConstruction construction,
                    CoherentWitnessFamily family) {
        Objects.requireNonNull(construction, "construction");
        return canonicalizeCertifiedWithConstruction(
                construction.node(), family, construction.certificate());
    }

    private CertifiedCanonicalizationResult canonicalizeCertifiedWithConstruction(
            TypedENode node,
            CoherentWitnessFamily family,
            TypedEqualityCertificate construction) {
        requireCurrentWitnessFamily(Objects.requireNonNull(family, "family"));
        CanonicalizationResult structural = canonicalizeWithConstruction(
                Objects.requireNonNull(node, "node"), construction);
        KernelReplayCertificate replay = KernelReplayCertificate.create(
                this, family, structural.leaderKernel());
        return new CertifiedCanonicalizationResult(
                structural, replay, construction);
    }

    /**
     * Bottom-up source/rewrite insertion through the coherent, certified
     * mutation boundary. A colliding key installs a certified parent edge and
     * leaves the ordinary Phase G dirty queue to {@link #rebuild()}.
     */
    public synchronized CertifiedInsertionResult insertNode(
            TypedENode node,
            CoherentWitnessFamily family) {
        return insertNodeWithConstruction(node, family, null);
    }

    public synchronized CertifiedInsertionResult insertNodeConstructed(
            CertifiedFlatConstruction construction,
            CoherentWitnessFamily family) {
        Objects.requireNonNull(construction, "construction");
        if (construction.collapsedToSingleton()) {
            throw new IllegalArgumentException(
                    "A smart-collapsed singleton has no operator node to insert");
        }
        return insertNodeWithConstruction(
                construction.node(), family, construction.certificate());
    }

    public synchronized CertifiedInsertionResult insertNodeConstructed(
            CertifiedContainerConstruction construction,
            CoherentWitnessFamily family) {
        Objects.requireNonNull(construction, "construction");
        return insertNodeWithConstruction(
                construction.node(), family, construction.certificate());
    }

    public synchronized CertifiedInsertionResult insertNodeConstructed(
            CertifiedDependentChainConstruction construction,
            CoherentWitnessFamily family) {
        Objects.requireNonNull(construction, "construction");
        return insertNodeWithConstruction(
                construction.node(), family, construction.certificate());
    }

    private CertifiedInsertionResult insertNodeWithConstruction(
            TypedENode node,
            CoherentWitnessFamily family,
            TypedEqualityCertificate construction) {
        CertificateTraceSnapshot before = traceSnapshot();
        TypedENode source = Objects.requireNonNull(node, "node");
        requireCurrentWitnessFamily(Objects.requireNonNull(family, "family"));
        requireCertifiedNodeTheory(source);
        for (PortValue port : source.ports()) {
            validatePortInvocations(port, false);
        }

        CertifiedCanonicalizationResult certified =
                canonicalizeCertifiedWithConstruction(
                        source, family, construction);
        CanonicalizationResult structural = certified.structural();
        source = structural.source();
        EClassId freshId = nextEClassId();
        TypedEClassInterface fresh = new TypedEClassInterface(
                freshId,
                source.outputType(),
                structural.effectiveSupport());
        CanonicalOrbitCertificate orbit = CanonicalOrbitCertificate.create(
                this, family, structural);
        FreshWitnessDefinitionCertificate definition =
                FreshWitnessDefinitionCertificate.create(
                        this, structural.kernel(), fresh, certified.d());
        TypedEqualityCertificate shapeEquation = EqualityCertificates.transitive(
                orbit, definition);
        ShapeWitness shapeWitness = new ShapeWitness(
                structural.shape().exactSlots(),
                structural.effectiveSupport(),
                fresh.exposedSlots(),
                structural.witness());
        TypedEClassRecord freshRecord = TypedEClassRecord.of(
                fresh,
                Collections.singletonMap(structural.shape(), shapeWitness),
                TypedSymmetryGroup.identity(fresh.exposedSlots()));
        shapeEquation = EffectiveShapeCollisionCertificate.orientShapeEquation(
                structural.shape(), freshRecord, shapeWitness, shapeEquation);
        CertificateVerifier.verify(shapeEquation);

        TypedEqualityCertificate sourceToFresh = EqualityCertificates.transitive(
                certified.d(),
                EqualityCertificates.rename(definition, structural.inclusion()));
        TypedInvocation freshInSource = new TypedInvocation(
                fresh, structural.inclusion());
        sourceToFresh = EqualityCertificates.orient(
                sourceToFresh,
                TypedCertificateEndpoint.node(source),
                TypedCertificateEndpoint.invocation(freshInSource));
        CertificateVerifier.verify(sourceToFresh);

        ParentEdgeCertificate collision = firstCollisionWithOwners(
                structural.shape(), freshRecord, shapeEquation);

        unionFind.register(fresh);
        classes.put(freshId, freshRecord);
        ParentRecordKey freshKey = new ParentRecordKey(freshId, structural.shape());
        shapeCertificates.put(freshKey, shapeEquation);
        indexRecord(freshKey);
        addHashOwner(structural.shape(), freshId);
        List<CertificateTracePayload.Union> generatedUnions = new ArrayList<>();
        if (collision != null) {
            generatedUnions.add(unionCertifiedInternal(collision).trace);
        }
        generatedUnions.addAll(resolveShapeCollisionsDetailed(
                structural.shape(), false));

        TypedFindResult returned = findNormalized(freshInSource, true);
        compressAllParentPaths();
        TypedEqualityCertificate sourceToReturned = returned.leaderInvocation()
                        .equals(freshInSource)
                ? sourceToFresh
                : EqualityCertificates.transitive(
                        sourceToFresh, returned.parentCertificate());
        CertificateVerifier.verify(sourceToReturned);
        CertifiedInsertionResult insertion = new CertifiedInsertionResult(
                certified,
                fresh,
                shapeWitness,
                orbit,
                definition,
                shapeEquation,
                sourceToFresh,
                collision,
                returned.leaderInvocation(),
                sourceToReturned);
        insertionHistory.put(freshId, insertion);
        coherenceRevision = Math.incrementExact(coherenceRevision);
        checkInvariants();
        appendTrace(
                collision == null
                        ? CertificateTraceEvent.Kind.INSERT_FRESH
                        : CertificateTraceEvent.Kind.INSERT_COLLISION,
                before,
                new CertificateTracePayload.Insertion(insertion, generatedUnions));
        return insertion;
    }

    private void requireConcreteConstruction(
            TypedENode node,
            TypedEqualityCertificate construction) {
        if (certificateMode != GraphCertificateMode.REQUIRED) {
            return;
        }
        boolean requiresConstruction = !node.operator().containerLaws().isEmpty();
        if (!requiresConstruction) {
            if (construction != null) {
                throw new IllegalArgumentException(
                        "A law-free node must not carry container-normalization evidence");
            }
            return;
        }
        if (construction == null) {
            throw new IllegalStateException(
                    "A production law-bearing node requires concrete source construction evidence");
        }
        CertificateVerifier.verify(construction);
        if (!TypedCertificateEndpoint.node(node).equals(
                construction.rightEndpoint())) {
            throw new IllegalArgumentException(
                    "Concrete source construction proves another target node");
        }
        if (construction instanceof FlatConstructionCertificate) {
            FlatConstructionCertificate flat =
                    (FlatConstructionCertificate) construction;
            if (flat.collapsedToSingleton()
                    || !semanticProfile.equals(flat.semanticProfile())) {
                throw new IllegalArgumentException(
                        "Flat construction target or profile does not match this graph");
            }
            return;
        }
        if (construction instanceof ContainerConstructionCertificate) {
            ContainerConstructionCertificate container =
                    (ContainerConstructionCertificate) construction;
            if (!semanticProfile.equals(container.semanticProfile())) {
                throw new IllegalArgumentException(
                        "Container construction uses another semantic profile");
            }
            return;
        }
        if (construction instanceof DependentChainCertificate) {
            DependentChainCertificate chain =
                    (DependentChainCertificate) construction;
            if (!semanticProfile.equals(chain.semanticProfile())) {
                throw new IllegalArgumentException(
                        "Dependent-chain construction uses another semantic profile");
            }
            return;
        }
        throw new IllegalArgumentException(
                "A production law-bearing node requires an admitted concrete construction");
    }

    private static TypedENode concreteConstructionTarget(
            TypedEqualityCertificate construction) {
        if (construction instanceof FlatConstructionCertificate) {
            FlatConstructionCertificate flat = (FlatConstructionCertificate) construction;
            if (flat.collapsedToSingleton()) {
                throw new IllegalArgumentException(
                        "A singleton collapse cannot own a fixed-batch operator shape");
            }
            return flat.target();
        }
        if (construction instanceof ContainerConstructionCertificate) {
            return ((ContainerConstructionCertificate) construction).target();
        }
        if (construction instanceof DependentChainCertificate) {
            return ((DependentChainCertificate) construction).target();
        }
        throw new IllegalArgumentException(
                "Fixed-batch construction evidence has the wrong certificate family");
    }

    private EClassId nextEClassId() {
        return classes.isEmpty()
                ? EClassId.of(0)
                : EClassId.of(Math.incrementExact(classes.lastKey().value()));
    }

    /** Extracts the Phase DA exact leader kernel and structural provenance. */
    synchronized LeaderKernelResult extractLeaderKernel(TypedENode node) {
        requireCertifiedNodeTheory(Objects.requireNonNull(node, "node"));
        return LeaderKernelExtractor.instance().extract(this, node);
    }

    synchronized LeaderKernelResult extractLeaderKernelWithoutCompression(
            TypedENode node) {
        requireCertifiedNodeTheory(Objects.requireNonNull(node, "node"));
        return LeaderKernelExtractor.instance().extractWithoutCompression(this, node);
    }

    synchronized TypedFindResult findWithoutCompressionForTesting(TypedInvocation invocation) {
        return findNormalized(Objects.requireNonNull(invocation, "invocation"), false);
    }

    /** Nonmutating provenance lookup used by the read-only Phase H oracle. */
    synchronized TypedFindResult findForFiniteUnfolding(TypedInvocation invocation) {
        TypedFindResult result = findNormalized(
                Objects.requireNonNull(invocation, "invocation"), false);
        checkInvariants();
        return result;
    }

    public synchronized TypedEClassRecord eclass(EClassId id) {
        TypedEClassRecord record = classes.get(Objects.requireNonNull(id, "id"));
        if (record == null) {
            throw new IllegalArgumentException("Unknown e-class id: " + id);
        }
        return record;
    }

    public synchronized Map<EClassId, TypedEClassRecord> classes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(classes));
    }

    public synchronized Map<EClassId, ParentAssignment> parentAssignments() {
        return unionFind.assignments();
    }

    public synchronized boolean isLeader(EClassId id) {
        eclass(id);
        return unionFind.isLeader(id);
    }

    public synchronized GraphStatus status() {
        return status;
    }

    synchronized void requireQuiescentForCanonicalization() {
        if (!rebuildActive) {
            requireQuiescent();
        }
    }

    synchronized void requireCertifiedNodeTheoryForCanonicalization(TypedENode node) {
        requireCertifiedNodeTheory(Objects.requireNonNull(node, "node"));
    }

    synchronized TypedSymmetryGroup symmetryGroupForCanonicalization(
            TypedEClassInterface eclass) {
        TypedEClassRecord record = this.eclass(eclass.id());
        if (!record.interfaceView().equals(eclass)) {
            throw new IllegalArgumentException("Stale e-class interface in canonicalization");
        }
        if (requiresCertificates()) {
            record.symmetryGroup().requireCertifiedFor(eclass);
        }
        return record.symmetryGroup();
    }

    synchronized BinderAutomorphismGroup binderGroupForCanonicalization(
            BinderBlockDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (requiresCertificates()
                && !descriptor.hasCertifiedAutomorphisms()) {
            throw new IllegalStateException(
                    "Canonicalization cannot use an uncertified binder automorphism");
        }
        return descriptor.automorphisms();
    }

    synchronized TypedFindResult findForCanonicalization(TypedInvocation invocation) {
        return findForCanonicalization(invocation, true);
    }

    synchronized TypedFindResult findForCanonicalization(
            TypedInvocation invocation,
            boolean allowCompression) {
        if (!rebuildActive) {
            requireQuiescent();
        }
        return findNormalized(
                Objects.requireNonNull(invocation, "invocation"),
                allowCompression && !rebuildActive);
    }

    /**
     * Closes an empty-shape test fixture after raw Phase D parent setup. This is
     * deliberately not a rebuild substitute and cannot publish stored shapes.
     */
    synchronized void sealEmptyShapeFixtureForPhaseE() {
        requireStructuralFixture("Phase E empty-shape seal");
        for (TypedEClassRecord record : classes.values()) {
            if (!record.shapeWitnesses().isEmpty()) {
                throw new IllegalStateException(
                        "Only an empty-shape fixture may use the Phase E setup seal");
            }
        }
        if (!hashCons.isEmpty()) {
            throw new IllegalStateException("An empty-shape fixture must have an empty hash-cons");
        }
        status = GraphStatus.QUIESCENT;
        checkInvariants();
    }

    public synchronized EClassId hashOwner(CanonicalShape shape) {
        requireQuiescent();
        NavigableSet<EClassId> owners = hashCons.get(
                Objects.requireNonNull(shape, "shape"));
        return owners == null ? null : owners.first();
    }

    /** Deterministic compatibility view selecting the least owner in each bucket. */
    public synchronized Map<CanonicalShape, EClassId> hashConsSnapshot() {
        requireQuiescent();
        Map<CanonicalShape, EClassId> snapshot = new LinkedHashMap<>();
        for (Map.Entry<CanonicalShape, NavigableSet<EClassId>> entry
                : hashCons.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().first());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /** Total exact-shape ownership, including incomparable certified leaders. */
    public synchronized Map<CanonicalShape, Set<EClassId>> hashBucketsSnapshot() {
        requireQuiescent();
        Map<CanonicalShape, Set<EClassId>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<CanonicalShape, NavigableSet<EClassId>> entry
                : hashCons.entrySet()) {
            snapshot.put(entry.getKey(), Collections.unmodifiableSet(
                    new LinkedHashSet<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public synchronized int dirtyParentCount() {
        return dirtyParents.size();
    }

    synchronized long collisionOrientationAttemptsForTesting() {
        return collisionOrientationAttempts;
    }

    public synchronized Map<ParentRecordKey, TypedEqualityCertificate>
            shapeCertificatesSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(shapeCertificates));
    }

    public synchronized Map<ParentRecordKey, RetiredShapeRecordCertificate>
            retiredShapeRecordsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(retiredShapeRecords));
    }

    /** Source-level provenance retained independently of current leader ownership. */
    public synchronized Map<EClassId, CertifiedInsertionResult>
            insertionHistorySnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(insertionHistory));
    }

    public synchronized Map<EClassId, Set<ParentRecordKey>> parentUsesSnapshot() {
        Map<EClassId, Set<ParentRecordKey>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<EClassId, NavigableSet<ParentRecordKey>> entry
                : parentUses.entrySet()) {
            snapshot.put(entry.getKey(), Collections.unmodifiableSet(
                    new LinkedHashSet<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /** Restores the fixed finite stored-node batch without inserting rewrites. */
    public synchronized RebuildReport rebuild() {
        return rebuild(false);
    }

    synchronized RebuildReport rebuildReverseForTesting() {
        return rebuild(true);
    }

    private RebuildReport rebuild(boolean reverseOrder) {
        if (!requiresCertificates()) {
            throw new IllegalStateException(
                    "The Phase G rebuild is available only in strict certificate mode");
        }
        if (rebuildActive) {
            throw new IllegalStateException("Rebuild is not reentrant");
        }
        if (status == GraphStatus.QUIESCENT) {
            checkInvariants();
            return new RebuildReport(0, 0, 0, 0, 0);
        }

        int processed = 0;
        int changed = 0;
        int collisions = 0;
        int unions = 0;
        int maximumDirty = dirtyParents.size();
        int initialRecordCount = shapeCertificates.size();
        int initialLeaderCount = liveLeaderCount();
        long processingBudget = rebuildProcessingBudget(
                maximumDirty, initialRecordCount, initialLeaderCount);
        int maximumUnions = Math.max(0, initialLeaderCount - 1);
        List<CertificateTracePayload.Union> generatedUnions = new ArrayList<>();
        List<CertificateTracePayload.RebuildRecord> processedTransitions =
                new ArrayList<>();
        long firstRebuildEvent = traceSequence;
        if (traceSink.enabled()) {
            CertificateTraceSnapshot beforeStart = traceSnapshot();
            appendTrace(
                    CertificateTraceEvent.Kind.REBUILD_START,
                    beforeStart,
                    new CertificateTracePayload.RebuildStart(
                            beforeStart.stateKey()));
        }
        rebuildActive = true;
        try {
            while (true) {
                while (!dirtyParents.isEmpty()) {
                    if ((long) processed >= processingBudget) {
                        throw new IllegalStateException(
                                "Rebuild exceeded its finite record-processing budget");
                    }
                    maximumDirty = Math.max(maximumDirty, dirtyParents.size());
                    ParentRecordKey key = reverseOrder
                            ? dirtyParents.last()
                            : dirtyParents.first();
                    CertificateTraceSnapshot beforeRecord = traceSnapshot();
                    dirtyParents.remove(key);
                    if (!recordExists(key)) {
                        throw new IllegalStateException(
                                "Dirty queue lost its live parent record before rebuild");
                    }
                    rebuildingRecord = key;
                    RebuildStepResult step;
                    try {
                        step = rebuildRecord(key);
                    } catch (RuntimeException exception) {
                        if (recordExists(key)) {
                            dirtyParents.add(key);
                        }
                        throw exception;
                    } finally {
                        rebuildingRecord = null;
                    }
                    processed = Math.incrementExact(processed);
                    changed = Math.addExact(changed, step.changed ? 1 : 0);
                    collisions = Math.addExact(
                            collisions, step.generatedSubtransitions.size());
                    unions = Math.addExact(
                            unions, step.generatedSubtransitions.size());
                    requireUnionBudget(unions, maximumUnions);
                    generatedUnions.addAll(step.generatedSubtransitions);
                    processedTransitions.add(step.trace);
                    checkInvariants();
                    appendTrace(
                            CertificateTraceEvent.Kind.REBUILD_RECORD,
                            beforeRecord,
                            step.trace);
                }
                List<CertificateTracePayload.Union> resolvedTransitions =
                        resolveAllShapeCollisionsDetailed(true);
                int resolved = resolvedTransitions.size();
                generatedUnions.addAll(resolvedTransitions);
                collisions = Math.addExact(collisions, resolved);
                unions = Math.addExact(unions, resolved);
                requireUnionBudget(unions, maximumUnions);
                if (resolved == 0 && dirtyParents.isEmpty()) {
                    // Quiescence is certified from a fresh compatibility pass,
                    // never solely from previously cached negative attempts.
                    incompatibleCollisions.clear();
                    List<CertificateTracePayload.Union> freshTransitions =
                            resolveAllShapeCollisionsDetailed(true);
                    int freshResolved = freshTransitions.size();
                    generatedUnions.addAll(freshTransitions);
                    collisions = Math.addExact(collisions, freshResolved);
                    unions = Math.addExact(unions, freshResolved);
                    requireUnionBudget(unions, maximumUnions);
                    if (freshResolved == 0 && dirtyParents.isEmpty()) {
                        break;
                    }
                }
            }
            requireHashBucketsExact();
            CertificateTraceSnapshot beforeCompletion = traceSnapshot();
            // Reconstruct from the authoritative live records instead of
            // trusting the incrementally maintained index at the boundary.
            // This makes the final rebuild a semantic operation: omitting it
            // leaves an observably incomplete owner index.
            hashCons.clear();
            rebuildHashConsExactly();
            requireHashBucketsExact();
            status = GraphStatus.QUIESCENT;
            if (changed != 0 || collisions != 0 || unions != 0) {
                coherenceRevision = Math.incrementExact(coherenceRevision);
            }
            checkInvariants();
            RebuildReport report = new RebuildReport(
                    processed,
                    changed,
                    collisions,
                    unions,
                    maximumDirty,
                    generatedUnions,
                    firstRebuildEvent,
                    processedTransitions);
            appendTrace(
                    CertificateTraceEvent.Kind.REBUILD_COMPLETE,
                    beforeCompletion,
                    new CertificateTracePayload.RebuildComplete(report));
            return report;
        } catch (RuntimeException exception) {
            status = GraphStatus.DIRTY;
            throw exception;
        } finally {
            rebuildingRecord = null;
            rebuildActive = false;
        }
    }

    /** Finite fixed-batch bound: each union can re-dirty at most every live record. */
    static long rebuildProcessingBudget(
            int initialDirty,
            int recordCount,
            int leaderCount) {
        if (initialDirty < 0 || recordCount < 0 || leaderCount < 0
                || initialDirty > recordCount) {
            throw new IllegalArgumentException(
                    "Rebuild counts must be nonnegative and dirty work must be live");
        }
        long unionBudget = Math.max(0L, (long) leaderCount - 1L);
        return Math.addExact(
                initialDirty,
                Math.multiplyExact((long) recordCount, unionBudget));
    }

    private static void requireUnionBudget(int unions, int maximumUnions) {
        if (unions > maximumUnions) {
            throw new IllegalStateException(
                    "Rebuild exceeded the strictly decreasing leader budget");
        }
    }

    private int liveLeaderCount() {
        int leaders = 0;
        for (EClassId id : classes.keySet()) {
            if (unionFind.isLeader(id)) {
                leaders = Math.incrementExact(leaders);
            }
        }
        return leaders;
    }

    private RebuildStepResult rebuildRecord(ParentRecordKey key) {
        TypedEClassRecord owner = eclass(key.owner());
        if (!unionFind.isLeader(owner.id())) {
            throw new IllegalStateException(
                    "Only leader-owned records may enter the Phase G rebuild queue");
        }
        ShapeWitness oldWitness = owner.shapeWitnesses().get(key.shape());
        if (oldWitness == null) {
            throw new IllegalStateException(
                    "A selected dirty record disappeared before canonicalization");
        }
        TypedEqualityCertificate oldEquation = requireShapeCertificate(key);
        CertificateTracePayload.ShapeRecord oldRecord = shapeRecord(
                key, owner, oldWitness, oldEquation);
        TypedENode source = key.shape().node().act(
                oldWitness.instantiatingRenaming());
        CanonicalizationResult result = ProductionGraphCanonicalizer.instance()
                .canonicalize(this, source);
        if (!owner.exposedSlots().isSubcontextOf(result.effectiveSupport())) {
            throw new InterfaceRestrictionRequiredException(
                    owner.interfaceView(), result.effectiveSupport());
        }

        TypedEqualityCertificate rebuildEquation =
                RebuildCongruenceCertificate.create(this, result);
        TypedEqualityCertificate ambientNewToOwner = EqualityCertificates.transitive(
                EqualityCertificates.symmetric(rebuildEquation), oldEquation);
        TypedEmbedding effectiveInOldAmbient = TypedEmbedding.inclusion(
                result.effectiveSupport(), oldWitness.ambientSupport());
        ShapeWitness newWitness = new ShapeWitness(
                result.shape().exactSlots(),
                result.effectiveSupport(),
                owner.exposedSlots(),
                result.witness());
        TypedCertificateEndpoint newNode = TypedCertificateEndpoint.node(
                result.shape().node().act(result.witness()));
        TypedCertificateEndpoint newOwner = TypedCertificateEndpoint.invocation(
                new TypedInvocation(
                        owner.interfaceView(),
                        TypedEmbedding.inclusion(
                                owner.exposedSlots(), result.effectiveSupport())));
        TypedEqualityCertificate newEquation = EqualityCertificates.restrict(
                ambientNewToOwner,
                newNode,
                newOwner,
                effectiveInOldAmbient);

        removeStoredRecord(key);
        ParentRecordKey replacementKey = new ParentRecordKey(
                owner.id(), result.shape());
        boolean duplicateInOwner = recordExists(replacementKey);
        CertificateTracePayload.ShapeRecord replacement = shapeRecord(
                replacementKey, owner, newWitness, newEquation);
        RetiredShapeRecordCertificate retirement = null;
        if (!duplicateInOwner) {
            installStoredRecord(
                    owner.id(), result.shape(), newWitness, newEquation);
            replacement = shapeRecord(
                    replacementKey,
                    eclass(owner.id()),
                    eclass(owner.id()).shapeWitnesses().get(result.shape()),
                    requireShapeCertificate(replacementKey));
        } else {
            CertificateTracePayload.ShapeRecord retained = shapeRecord(
                    replacementKey,
                    eclass(owner.id()),
                    eclass(owner.id()).shapeWitnesses().get(result.shape()),
                    requireShapeCertificate(replacementKey));
            retirement = RetiredShapeRecordCertificate.rebuildDuplicate(
                    oldRecord,
                    replacement,
                    retained,
                    result,
                    rebuildEquation);
            RetiredShapeRecordCertificate prior = retiredShapeRecords.putIfAbsent(
                    key, retirement);
            if (prior != null && !prior.structuralKey().equals(
                    retirement.structuralKey())) {
                throw new IllegalStateException(
                        "One rebuilt record has conflicting retirement evidence");
            }
        }
        boolean changed = !key.shape().equals(result.shape())
                || !oldWitness.equals(newWitness)
                || duplicateInOwner;
        List<CertificateTracePayload.Union> generatedUnions =
                resolveShapeCollisionsDetailed(result.shape(), false);
        int unions = generatedUnions.size();
        CertificateTracePayload.RebuildRecord trace =
                new CertificateTracePayload.RebuildRecord(
                        oldRecord,
                        result,
                        rebuildEquation,
                        duplicateInOwner ? null : replacement,
                        retirement,
                        generatedUnions,
                        changed || unions != 0);
        return new RebuildStepResult(
                changed || unions != 0,
                trace,
                generatedUnions);
    }

    private ParentEdgeCertificate firstCollisionWithOwners(
            CanonicalShape shape,
            TypedEClassRecord candidate,
            TypedEqualityCertificate candidateEquation) {
        NavigableSet<EClassId> owners = hashCons.get(shape);
        if (owners == null) {
            return null;
        }
        for (EClassId ownerId : new ArrayList<>(owners)) {
            if (ownerId.equals(candidate.id()) || !unionFind.isLeader(ownerId)) {
                continue;
            }
            TypedEClassRecord owner = eclass(ownerId);
            if (!owner.shapeWitnesses().containsKey(shape)) {
                continue;
            }
            ParentEdgeCertificate collision = collisionParentEdge(
                    shape,
                    candidate,
                    candidateEquation,
                    owner,
                    requireShapeCertificate(new ParentRecordKey(ownerId, shape)));
            if (collision != null) {
                return collision;
            }
        }
        return null;
    }

    /** Resolves every provable pair and leaves incomparable leaders in the bucket. */
    private int resolveShapeCollisions(CanonicalShape shape) {
        return resolveShapeCollisionsDetailed(shape, false).size();
    }

    private List<CertificateTracePayload.Union> resolveShapeCollisionsDetailed(
            CanonicalShape shape,
            boolean emitTraceEvents) {
        List<CertificateTracePayload.Union> unions = new ArrayList<>();
        while (true) {
            NavigableSet<EClassId> owners = hashCons.get(shape);
            if (owners == null || owners.size() < 2) {
                return unions;
            }
            List<EClassId> ordered = new ArrayList<>(owners);
            ParentEdgeCertificate collision = null;
            for (int left = 0; left < ordered.size() && collision == null; left++) {
                EClassId leftId = ordered.get(left);
                if (!unionFind.isLeader(leftId)) {
                    removeHashOwner(shape, leftId);
                    continue;
                }
                TypedEClassRecord leftRecord = eclass(leftId);
                if (!leftRecord.shapeWitnesses().containsKey(shape)) {
                    removeHashOwner(shape, leftId);
                    continue;
                }
                for (int right = left + 1; right < ordered.size(); right++) {
                    EClassId rightId = ordered.get(right);
                    if (!unionFind.isLeader(rightId)) {
                        removeHashOwner(shape, rightId);
                        continue;
                    }
                    TypedEClassRecord rightRecord = eclass(rightId);
                    if (!rightRecord.shapeWitnesses().containsKey(shape)) {
                        removeHashOwner(shape, rightId);
                        continue;
                    }
                    collision = collisionParentEdge(
                            shape,
                            leftRecord,
                            requireShapeCertificate(new ParentRecordKey(leftId, shape)),
                            rightRecord,
                            requireShapeCertificate(new ParentRecordKey(rightId, shape)));
                    if (collision != null) {
                        break;
                    }
                }
            }
            if (collision == null) {
                return unions;
            }
            CertificateTraceSnapshot before = emitTraceEvents
                    ? traceSnapshot() : null;
            UnionMutation mutation = unionCertifiedInternal(collision);
            unions.add(mutation.trace);
            if (emitTraceEvents) {
                checkInvariants();
                appendTrace(
                        CertificateTraceEvent.Kind.UNION,
                        before,
                        mutation.trace);
            }
        }
    }

    private List<CertificateTracePayload.Union> resolveAllShapeCollisionsDetailed(
            boolean emitTraceEvents) {
        List<CertificateTracePayload.Union> unions = new ArrayList<>();
        for (CanonicalShape shape : new ArrayList<>(hashCons.keySet())) {
            unions.addAll(resolveShapeCollisionsDetailed(shape, emitTraceEvents));
        }
        return unions;
    }

    private void addHashOwner(CanonicalShape shape, EClassId owner) {
        hashCons.computeIfAbsent(shape, ignored -> new TreeSet<>()).add(owner);
    }

    private void removeHashOwner(CanonicalShape shape, EClassId owner) {
        NavigableSet<EClassId> owners = hashCons.get(shape);
        if (owners == null) {
            return;
        }
        owners.remove(owner);
        if (owners.isEmpty()) {
            hashCons.remove(shape);
        }
    }

    private ParentEdgeCertificate collisionParentEdge(
            CanonicalShape shape,
            TypedEClassRecord first,
            TypedEqualityCertificate firstEquation,
            TypedEClassRecord second,
            TypedEqualityCertificate secondEquation) {
        CollisionPairKey pair = new CollisionPairKey(shape, first.id(), second.id());
        Long rejectedAt = incompatibleCollisions.get(pair);
        if (rejectedAt != null && rejectedAt.longValue() == coherenceRevision) {
            return null;
        }
        EClassId preferredParent = first.id().compareTo(second.id()) < 0
                ? first.id() : second.id();
        EClassId preferredChild = preferredParent.equals(first.id())
                ? second.id() : first.id();
        ParentEdgeCertificate preferred = null;
        ParentEdgeCertificate opposite = null;
        try {
            preferred = collisionParentEdgeOriented(
                    shape,
                    preferredChild.equals(first.id()) ? first : second,
                    preferredChild.equals(first.id()) ? firstEquation : secondEquation,
                    preferredParent.equals(first.id()) ? first : second,
                    preferredParent.equals(first.id()) ? firstEquation : secondEquation);
        } catch (EffectiveShapeCollisionCertificate.IncompatibleInterfaces exception) {
            // The preferred directed embedding is not inhabited.
        }
        try {
            opposite = collisionParentEdgeOriented(
                    shape,
                    preferredParent.equals(first.id()) ? first : second,
                    preferredParent.equals(first.id()) ? firstEquation : secondEquation,
                    preferredChild.equals(first.id()) ? first : second,
                    preferredChild.equals(first.id()) ? firstEquation : secondEquation);
        } catch (EffectiveShapeCollisionCertificate.IncompatibleInterfaces exception) {
            // The opposite directed embedding is not inhabited.
        }
        if (preferred != null) {
            return preferred;
        }
        if (opposite != null) {
            return opposite;
        }
        {
            incompatibleCollisions.put(pair, coherenceRevision);
            return null;
        }
    }

    private ParentEdgeCertificate collisionParentEdgeOriented(
            CanonicalShape shape,
            TypedEClassRecord child,
            TypedEqualityCertificate childEquation,
            TypedEClassRecord parent,
            TypedEqualityCertificate parentEquation) {
        collisionOrientationAttempts = Math.addExact(
                collisionOrientationAttempts, 1L);
        EffectiveShapeCollisionCertificate collision =
                EffectiveShapeCollisionCertificate.create(
                        shape,
                        child,
                        child.shapeWitnesses().get(shape),
                        childEquation,
                        parent,
                        parent.shapeWitnesses().get(shape),
                        parentEquation);
        CertificateVerifier.verify(collision);
        return new ParentEdgeCertificate(
                collision.child(),
                collision.parentInvocation(),
                collision);
    }

    private UnionMutation unionCertifiedInternal(ParentEdgeCertificate certificate) {
        ParentEdgeCertificate checked = Objects.requireNonNull(
                certificate, "certificate");
        CertificateVerifier.verifyParentEdge(checked);
        requireRecord(checked.child());
        requireRecord(checked.parent());
        if (checked.child().id().equals(checked.parent().id())
                || !unionFind.isLeader(checked.child().id())
                || !unionFind.isLeader(checked.parent().id())) {
            throw new IllegalArgumentException(
                    "Certified union requires current distinct leaders");
        }

        TypedEClassRecord child = eclass(checked.child().id());
        TypedEClassRecord parent = eclass(checked.parent().id());
        TypedSymmetryGroup mergedGroup = mergeStabilizingSymmetries(
                child, parent, checked);
        TypedEClassRecord updatedParent = parent.withSymmetryGroup(mergedGroup);
        List<CertificateTracePayload.ShapeRehome> rehomes = new ArrayList<>();
        List<RetiredShapeRecordCertificate> retirements = new ArrayList<>();
        List<CanonicalShape> childShapes = new ArrayList<>(
                child.shapeWitnesses().keySet());
        for (CanonicalShape shape : childShapes) {
            ParentRecordKey oldKey = new ParentRecordKey(child.id(), shape);
            ShapeWitness oldWitness = child.shapeWitnesses().get(shape);
            TypedEqualityCertificate oldEquation = requireShapeCertificate(oldKey);
            CertificateTracePayload.ShapeRecord oldRecord = shapeRecord(
                    oldKey, child, oldWitness, oldEquation);
            boolean wasDirty = dirtyParents.remove(oldKey);
            TransferredShape transferred = transferShapeToParent(
                    shape,
                    child,
                    oldWitness,
                    oldEquation,
                    parent,
                    checked);
            unindexRecord(oldKey);
            shapeCertificates.remove(oldKey);
            removeHashOwner(shape, child.id());

            ParentRecordKey newKey = new ParentRecordKey(parent.id(), shape);
            CertificateTracePayload.ShapeRecord replacementRecord = shapeRecord(
                    newKey,
                    parent,
                    transferred.witness,
                    transferred.equation);
            if (!updatedParent.shapeWitnesses().containsKey(shape)) {
                updatedParent = updatedParent.withStoredShape(
                        shape, transferred.witness);
                shapeCertificates.put(newKey, transferred.equation);
                indexRecord(newKey);
                rehomes.add(new CertificateTracePayload.ShapeRehome(
                        oldRecord,
                        shapeRecord(
                                newKey,
                                updatedParent,
                                transferred.witness,
                                requireShapeCertificate(newKey))));
                if (wasDirty) {
                    dirtyParents.add(newKey);
                }
            } else {
                CertificateTracePayload.ShapeRecord retainedRecord = shapeRecord(
                        newKey,
                        updatedParent,
                        updatedParent.shapeWitnesses().get(shape),
                        requireShapeCertificate(newKey));
                RetiredShapeRecordCertificate retirement =
                        RetiredShapeRecordCertificate.ownerUnion(
                                oldRecord,
                                replacementRecord,
                                retainedRecord,
                                checked);
                RetiredShapeRecordCertificate prior = retiredShapeRecords.putIfAbsent(
                        oldKey, retirement);
                if (prior != null && !prior.structuralKey().equals(
                        retirement.structuralKey())) {
                    throw new IllegalStateException(
                            "One owner-qualified record has conflicting retirement evidence");
                }
                retirements.add(retirement);
            }
            addHashOwner(shape, parent.id());
        }

        classes.put(parent.id(), updatedParent);
        classes.put(child.id(), child.withoutStoredShapes());
        ParentStep step = ParentStep.certified(checked);
        unionFind.linkRoots(step);
        markParentsDirty(child.id());
        if (!mergedGroup.equals(parent.symmetryGroup())) {
            markParentsDirty(parent.id());
        }
        status = GraphStatus.DIRTY;
        return new UnionMutation(
                step,
                new CertificateTracePayload.Union(
                        checked, rehomes, retirements));
    }

    private TransferredShape transferShapeToParent(
            CanonicalShape shape,
            TypedEClassRecord child,
            ShapeWitness witness,
            TypedEqualityCertificate equation,
            TypedEClassRecord parent,
            ParentEdgeCertificate edge) {
        TypedEqualityCertificate oriented =
                EffectiveShapeCollisionCertificate.orientShapeEquation(
                        shape, child, witness, equation);
        TypedRenaming relabeling = parentLiteralRelabeling(
                witness.ambientSupport(), edge.embedding());
        TypedRenaming instantiation = witness.instantiatingRenaming()
                .andThen(relabeling);
        ShapeWitness transported = new ShapeWitness(
                witness.exactSlots(),
                relabeling.codomain(),
                parent.exposedSlots(),
                instantiation);

        TypedEmbedding childInAmbient = TypedEmbedding.inclusion(
                child.exposedSlots(), witness.ambientSupport());
        TypedEqualityCertificate parentInOldAmbient = EqualityCertificates.transitive(
                oriented,
                EqualityCertificates.rename(edge, childInAmbient));
        TypedEqualityCertificate transportedEquation = EqualityCertificates.rename(
                parentInOldAmbient, relabeling);
        TypedEqualityCertificate checkedEquation =
                EffectiveShapeCollisionCertificate.orientShapeEquation(
                        shape, parent, transported, transportedEquation);
        return new TransferredShape(transported, checkedEquation);
    }

    private TypedRenaming parentLiteralRelabeling(
            TypedSlotContext ambient,
            TypedEmbedding parentInChild) {
        Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
        Set<TypedSlot> forcedSources = new HashSet<>();
        Set<TypedSlot> usedTargets = new HashSet<>();
        for (TypedSlot parentSlot : parentInChild.source()) {
            TypedSlot childSlot = parentInChild.apply(parentSlot);
            if (!ambient.contains(childSlot) || !forcedSources.add(childSlot)) {
                throw new IllegalStateException(
                        "Parent embedding is incompatible with a child shape ambient context");
            }
            mapping.put(childSlot, parentSlot);
            usedTargets.add(parentSlot);
        }
        for (TypedSlot source : ambient) {
            if (forcedSources.contains(source)) {
                continue;
            }
            TypedSlot target = source;
            if (usedTargets.contains(target)) {
                target = freshSourceSlot(source.type(), usedTargets);
            }
            mapping.put(source, target);
            usedTargets.add(target);
        }
        return TypedRenaming.of(
                ambient, TypedSlotContext.of(mapping.values()), mapping);
    }

    private static TypedSlot freshSourceSlot(
            GraphType type,
            Set<TypedSlot> occupied) {
        BigInteger ordinal = BigInteger.ZERO;
        while (occupied.contains(TypedSlot.of(type, SlotAlphabet.SOURCE, ordinal))) {
            ordinal = ordinal.add(BigInteger.ONE);
        }
        return TypedSlot.of(type, SlotAlphabet.SOURCE, ordinal);
    }

    static TypedSymmetryGroup mergeStabilizingSymmetries(
            TypedEClassRecord child,
            TypedEClassRecord parent,
            ParentEdgeCertificate edge) {
        TypedSymmetryGroup[] result = {parent.symmetryGroup()};
        child.symmetryGroup().forEachElement(childPermutation -> {
            TypedPermutation induced = inducedPermutation(
                    edge.embedding(), childPermutation);
            if (induced == null
                    || induced.equals(TypedPermutation.identity(parent.exposedSlots()))
                    || result[0].contains(induced)) {
                return;
            }
            TypedEqualityCertificate childSymmetry = child.symmetryGroup()
                    .derivationFor(child.interfaceView(), childPermutation);
            TypedEqualityCertificate ambient = EqualityCertificates.transitive(
                    EqualityCertificates.transitive(
                            EqualityCertificates.symmetric(edge), childSymmetry),
                    EqualityCertificates.rename(edge, childPermutation));
            TypedEqualityCertificate restricted = EqualityCertificates.restrict(
                    ambient,
                    TypedCertificateEndpoint.eclassWitness(parent.interfaceView()),
                    TypedCertificateEndpoint.invocation(
                            new TypedInvocation(parent.interfaceView(), induced)),
                    edge.embedding());
            SymmetryCertificate transported = new SymmetryCertificate(
                    TypedInvocation.identity(parent.interfaceView()),
                    new TypedInvocation(parent.interfaceView(), induced),
                    restricted);
            result[0] = result[0].withCertifiedGenerator(
                    parent.interfaceView(), transported);
        });
        return result[0];
    }

    private void compressAllParentPaths() {
        for (EClassId id : new ArrayList<>(classes.keySet())) {
            unionFind.findWithProvenance(
                    TypedInvocation.identity(eclass(id).interfaceView()));
        }
    }

    static TypedSymmetryGroup restrictSymmetryGroup(
            TypedEClassRecord original,
            InterfaceRestrictionCertificate restriction) {
        List<SymmetryCertificate> certificates = new ArrayList<>();
        TypedEClassInterface replacement = restriction.restrictedInterface();
        original.symmetryGroup().forEachElement(permutation -> {
            TypedPermutation induced = inducedPermutation(
                    restriction.inclusion(), permutation);
            if (induced == null
                    || induced.equals(TypedPermutation.identity(
                            replacement.exposedSlots()))) {
                return;
            }
            TypedEqualityCertificate oldSymmetry = original.symmetryGroup()
                    .derivationFor(original.interfaceView(), permutation);
            TypedEqualityCertificate ambient = EqualityCertificates.transitive(
                    EqualityCertificates.transitive(
                            EqualityCertificates.symmetric(restriction.factorization()),
                            oldSymmetry),
                    EqualityCertificates.rename(
                            restriction.factorization(), permutation));
            TypedEqualityCertificate restricted = EqualityCertificates.restrict(
                    ambient,
                    TypedCertificateEndpoint.eclassWitness(replacement),
                    TypedCertificateEndpoint.invocation(
                            new TypedInvocation(replacement, induced)),
                    restriction.inclusion());
            certificates.add(new SymmetryCertificate(
                    TypedInvocation.identity(replacement),
                    new TypedInvocation(replacement, induced),
                    restricted));
        });
        return TypedSymmetryGroup.certified(replacement, certificates);
    }

    private static TypedPermutation inducedPermutation(
            TypedEmbedding subcontextEmbedding,
            TypedPermutation ambientPermutation) {
        Map<TypedSlot, TypedSlot> inverseImage = new LinkedHashMap<>();
        for (TypedSlot source : subcontextEmbedding.source()) {
            inverseImage.put(subcontextEmbedding.apply(source), source);
        }
        Map<TypedSlot, TypedSlot> induced = new LinkedHashMap<>();
        for (TypedSlot source : subcontextEmbedding.source()) {
            TypedSlot moved = ambientPermutation.apply(
                    subcontextEmbedding.apply(source));
            TypedSlot target = inverseImage.get(moved);
            if (target == null) {
                return null;
            }
            induced.put(source, target);
        }
        return TypedPermutation.of(subcontextEmbedding.source(), induced);
    }

    private TypedEqualityCertificate requireShapeCertificate(ParentRecordKey key) {
        TypedEqualityCertificate certificate = shapeCertificates.get(key);
        if (certificate == null) {
            throw new IllegalStateException(
                    "Strict stored record has no exact shape equation: " + key);
        }
        CertificateVerifier.verify(certificate);
        return certificate;
    }

    private static CertificateTracePayload.ShapeRecord shapeRecord(
            ParentRecordKey key,
            TypedEClassRecord owner,
            ShapeWitness witness,
            TypedEqualityCertificate equation) {
        return new CertificateTracePayload.ShapeRecord(
                key,
                owner.interfaceView(),
                witness,
                equation);
    }

    private boolean recordExists(ParentRecordKey key) {
        TypedEClassRecord record = classes.get(key.owner());
        return record != null && record.shapeWitnesses().containsKey(key.shape());
    }

    private void removeStoredRecord(ParentRecordKey key) {
        TypedEClassRecord owner = eclass(key.owner());
        if (!owner.shapeWitnesses().containsKey(key.shape())) {
            return;
        }
        invalidateCollisionMemo(key);
        removeHashOwner(key.shape(), key.owner());
        unindexRecord(key);
        shapeCertificates.remove(key);
        classes.put(owner.id(), owner.withoutStoredShape(key.shape()));
    }

    private void installStoredRecord(
            EClassId ownerId,
            CanonicalShape shape,
            ShapeWitness witness,
            TypedEqualityCertificate equation) {
        TypedEClassRecord owner = eclass(ownerId);
        ParentRecordKey key = new ParentRecordKey(ownerId, shape);
        if (owner.shapeWitnesses().containsKey(shape)) {
            throw new IllegalArgumentException("Duplicate stored record key: " + key);
        }
        invalidateCollisionMemo(key);
        TypedEClassRecord updated = owner.withStoredShape(shape, witness);
        TypedEqualityCertificate oriented =
                EffectiveShapeCollisionCertificate.orientShapeEquation(
                        shape, updated, witness, equation);
        classes.put(ownerId, updated);
        shapeCertificates.put(key, oriented);
        addHashOwner(shape, ownerId);
        indexRecord(key);
    }

    private void invalidateCollisionMemo(ParentRecordKey changed) {
        incompatibleCollisions.keySet().removeIf(pair ->
                pair.shape.equals(changed.shape())
                        && (pair.first.equals(changed.owner())
                                || pair.second.equals(changed.owner())));
    }

    private void indexRecord(ParentRecordKey key) {
        for (EClassId child : invocationIds(key.shape().node())) {
            parentUses.computeIfAbsent(child, ignored -> new TreeSet<>()).add(key);
        }
    }

    private void unindexRecord(ParentRecordKey key) {
        for (EClassId child : invocationIds(key.shape().node())) {
            NavigableSet<ParentRecordKey> uses = parentUses.get(child);
            if (uses == null) {
                continue;
            }
            uses.remove(key);
            if (uses.isEmpty()) {
                parentUses.remove(child);
            }
        }
    }

    private void markParentsDirty(EClassId child) {
        NavigableSet<ParentRecordKey> uses = parentUses.get(child);
        if (uses != null) {
            dirtyParents.addAll(uses);
        }
    }

    private static Set<EClassId> invocationIds(TypedENode node) {
        Set<EClassId> result = new TreeSet<>();
        for (PortValue port : node.ports()) {
            collectInvocationIds(port, result);
        }
        return result;
    }

    private static void collectInvocationIds(
            PortValue port,
            Set<EClassId> target) {
        if (port instanceof OnePort) {
            PortLeaf leaf = ((OnePort) port).leaf();
            if (leaf instanceof InvocationPortLeaf) {
                target.add(((InvocationPortLeaf) leaf).invocation().eclass().id());
            }
        } else if (port instanceof SeqPort) {
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

    private TypedFindResult findNormalized(
            TypedInvocation original,
            boolean compress) {
        InvocationNormalization normalization = normalizeInvocation(original);
        TypedFindResult current = compress
                ? unionFind.findWithProvenance(normalization.invocation)
                : unionFind.findWithoutCompression(normalization.invocation);
        if (normalization.invocation.equals(original)) {
            return current;
        }
        return new TypedFindResult(
                original,
                normalization.invocation,
                current.leaderInvocation(),
                current.parentPath(),
                normalization.certificate);
    }

    private InvocationNormalization normalizeInvocation(TypedInvocation original) {
        TypedEClassRecord record = classes.get(original.eclass().id());
        if (record == null) {
            throw new IllegalArgumentException(
                    "Unknown e-class id: " + original.eclass().id());
        }
        if (!record.outputType().equals(original.outputType())) {
            throw new IllegalArgumentException(
                    "Historical invocation changed e-class output type");
        }
        if (record.interfaceView().equals(original.eclass())) {
            return new InvocationNormalization(
                    original,
                    EqualityCertificates.reflexive(
                            TypedCertificateEndpoint.invocation(original)));
        }

        TypedInvocation current = original;
        TypedEqualityCertificate proof = null;
        List<InterfaceRestrictionCertificate> history = restrictionHistory.get(
                original.eclass().id());
        if (history != null) {
            for (InterfaceRestrictionCertificate restriction : history) {
                if (!restriction.originalInterface().equals(current.eclass())) {
                    continue;
                }
                TypedEmbedding oldEmbedding = current.embedding();
                TypedInvocation next = new TypedInvocation(
                        restriction.restrictedInterface(),
                        restriction.inclusion().andThen(oldEmbedding));
                TypedEqualityCertificate step = EqualityCertificates.rename(
                        restriction.factorization(), oldEmbedding);
                proof = proof == null
                        ? step
                        : EqualityCertificates.transitive(proof, step);
                current = next;
            }
        }
        if (!record.interfaceView().equals(current.eclass()) || proof == null) {
            throw new IllegalArgumentException(
                    "Invocation e-class metadata is neither current nor certified historical state");
        }
        CertificateVerifier.verify(proof);
        return new InvocationNormalization(current, proof);
    }

    private void requireHashBucketsExact() {
        NavigableMap<CanonicalShape, NavigableSet<EClassId>> expected = new TreeMap<>();
        for (TypedEClassRecord record : classes.values()) {
            if (!unionFind.isLeader(record.id())) {
                continue;
            }
            for (CanonicalShape shape : record.shapeWitnesses().keySet()) {
                expected.computeIfAbsent(shape, ignored -> new TreeSet<>())
                        .add(record.id());
            }
        }
        if (!expected.equals(hashCons)) {
            throw new IllegalStateException(
                    "Hash buckets do not exactly match live leader ownership");
        }
    }

    private void rebuildHashConsExactly() {
        hashCons.clear();
        for (TypedEClassRecord record : classes.values()) {
            if (!unionFind.isLeader(record.id())) {
                continue;
            }
            for (CanonicalShape shape : record.shapeWitnesses().keySet()) {
                addHashOwner(shape, record.id());
            }
        }
        hashIndexRebuilds = Math.incrementExact(hashIndexRebuilds);
    }

    synchronized long hashIndexRebuildCount() {
        return hashIndexRebuilds;
    }

    public synchronized StructuralKey stateStructuralKey() {
        List<StructuralKey> children = new ArrayList<>();
        children.add(StructuralKey.leaf("graph-status", status.name()));
        children.add(StructuralKey.leaf(
                "graph-certificate-mode", certificateMode.name()));
        children.add(semanticProfile == null
                ? StructuralKey.leaf("semantic-profile", "structural-fixture")
                : semanticProfile.structuralKey());
        children.add(StructuralKey.leaf(
                "coherence-revision", Long.toString(coherenceRevision)));
        for (TypedEClassRecord record : classes.values()) {
            children.add(record.structuralKey());
        }
        for (ParentAssignment assignment : unionFind.assignments().values()) {
            children.add(assignment.structuralKey());
        }
        for (Map.Entry<CanonicalShape, NavigableSet<EClassId>> entry
                : hashCons.entrySet()) {
            for (EClassId owner : entry.getValue()) {
                children.add(StructuralKey.of(
                        "hash-owner",
                        Collections.singletonList(Long.toString(owner.value())),
                        Collections.singletonList(entry.getKey().structuralKey())));
            }
        }
        for (Map.Entry<ParentRecordKey, TypedEqualityCertificate> entry
                : shapeCertificates.entrySet()) {
            children.add(StructuralKey.branch(
                    "shape-equation",
                    java.util.Arrays.asList(
                            entry.getKey().structuralKey(),
                            entry.getValue().structuralKey())));
        }
        for (Map.Entry<ParentRecordKey, RetiredShapeRecordCertificate> entry
                : retiredShapeRecords.entrySet()) {
            children.add(StructuralKey.branch(
                    "retired-shape-record",
                    java.util.Arrays.asList(
                            entry.getKey().structuralKey(),
                            entry.getValue().structuralKey())));
        }
        for (Map.Entry<EClassId, NavigableSet<ParentRecordKey>> entry
                : parentUses.entrySet()) {
            List<StructuralKey> uses = new ArrayList<>();
            uses.add(StructuralKey.leaf(
                    "child", Long.toString(entry.getKey().value())));
            for (ParentRecordKey key : entry.getValue()) {
                uses.add(key.structuralKey());
            }
            children.add(StructuralKey.branch("parent-use-index", uses));
        }
        for (ParentRecordKey key : dirtyParents) {
            children.add(StructuralKey.branch(
                    "dirty-parent", Collections.singletonList(key.structuralKey())));
        }
        for (Map.Entry<EClassId, CertifiedInsertionResult> entry
                : insertionHistory.entrySet()) {
            children.add(StructuralKey.branch(
                    "source-insertion-provenance",
                    java.util.Arrays.asList(
                            StructuralKey.leaf(
                                    "inserted-class", Long.toString(entry.getKey().value())),
                            entry.getValue().structuralKey())));
        }
        return StructuralKey.branch("typed-slotted-port-egraph", children);
    }

    /** Final state capture for an explicit proof-retaining export session. */
    public synchronized CertificateTraceSnapshot certificateTraceSnapshot() {
        if (!traceSink.enabled()) {
            throw new IllegalStateException(
                    "The ordinary graph does not retain an export trace");
        }
        checkInvariants();
        return traceSnapshot();
    }

    private CertificateTraceSnapshot traceSnapshot() {
        if (!traceSink.enabled()) {
            return null;
        }
        Map<EClassId, Set<ParentRecordKey>> useCopies = new LinkedHashMap<>();
        for (Map.Entry<EClassId, NavigableSet<ParentRecordKey>> entry
                : parentUses.entrySet()) {
            useCopies.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        return new CertificateTraceSnapshot(
                coherenceRevision,
                status,
                classes,
                unionFind.assignments(),
                hashCons,
                shapeCertificates,
                retiredShapeRecords,
                useCopies,
                dirtyParents,
                restrictionHistory,
                insertionHistory);
    }

    private void appendTrace(
            CertificateTraceEvent.Kind kind,
            CertificateTraceSnapshot before,
            CertificateTracePayload payload) {
        if (!traceSink.enabled()) {
            return;
        }
        if (before == null) {
            throw new IllegalStateException("Enabled certificate trace has no pre-state");
        }
        CertificateTraceSnapshot after = traceSnapshot();
        before.verifyConservationTo(after, payload);
        traceSink.append(new CertificateTraceEvent(
                traceSequence++, kind, before, after, payload));
    }

    public synchronized void checkInvariants() {
        unionFind.checkInvariants();
        if (!classes.keySet().equals(unionFind.interfaces().keySet())) {
            throw new IllegalStateException("U and M have different e-class domains");
        }
        NavigableSet<ParentRecordKey> expectedShapeKeys = new TreeSet<>();
        NavigableMap<EClassId, NavigableSet<ParentRecordKey>> expectedUses =
                new TreeMap<>();
        for (Map.Entry<EClassId, TypedEClassRecord> entry : classes.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) {
                throw new IllegalStateException("E-class record is stored under the wrong id");
            }
            TypedEClassInterface registered = unionFind.interfaces().get(entry.getKey());
            if (!entry.getValue().interfaceView().equals(registered)) {
                throw new IllegalStateException("U and M disagree on an e-class interface");
            }
            if (requiresCertificates()
                    && !unionFind.isLeader(entry.getKey())
                    && !entry.getValue().shapeWitnesses().isEmpty()) {
                throw new IllegalStateException(
                        "A strict nonleader may not retain stored shapes");
            }
            if (requiresCertificates()) {
                entry.getValue().symmetryGroup().requireCertifiedFor(
                        entry.getValue().interfaceView());
            }
            for (Map.Entry<CanonicalShape, ShapeWitness> stored
                    : entry.getValue().shapeWitnesses().entrySet()) {
                CanonicalShape shape = stored.getKey();
                ParentRecordKey key = new ParentRecordKey(entry.getKey(), shape);
                expectedShapeKeys.add(key);
                validateStoredInvocationsState(key, shape.node());
                for (EClassId child : invocationIds(shape.node())) {
                    expectedUses.computeIfAbsent(
                            child, ignored -> new TreeSet<>()).add(key);
                }
                if (requiresCertificates()) {
                    requireCertifiedNodeTheory(shape.node());
                    TypedEqualityCertificate equation = requireShapeCertificate(key);
                    EffectiveShapeCollisionCertificate.orientShapeEquation(
                            shape, entry.getValue(), stored.getValue(), equation);
                }
            }
        }
        if (requiresCertificates()
                && !expectedShapeKeys.equals(shapeCertificates.navigableKeySet())) {
            throw new IllegalStateException(
                    "Strict shape records and exact shape equations differ");
        }
        for (Map.Entry<ParentRecordKey, RetiredShapeRecordCertificate> entry
                : retiredShapeRecords.entrySet()) {
            RetiredShapeRecordCertificate retirement = entry.getValue();
            retirement.verify();
            if (!entry.getKey().equals(retirement.retiredRecord())
                    || (retirement.cause()
                                    == RetiredShapeRecordCertificate.Cause.OWNER_UNION
                            && unionFind.isLeader(retirement.retiredRecord().owner()))
                    || (retirement.cause()
                                    == RetiredShapeRecordCertificate.Cause.REBUILD_DUPLICATE
                            && recordExists(retirement.retiredRecord()))) {
                throw new IllegalStateException(
                        "Retirement ledger contains a live or mismatched old record");
            }
        }
        if (!expectedUses.equals(parentUses)) {
            throw new IllegalStateException(
                    "Reverse parent-use index is not exact");
        }
        for (ParentRecordKey dirty : dirtyParents) {
            if (!recordExists(dirty)) {
                throw new IllegalStateException(
                        "Dirty queue references a missing parent record");
            }
        }
        if (requiresCertificates()) {
            for (ParentAssignment assignment : unionFind.assignments().values()) {
                if (!assignment.provenancePath().hasCertificates()) {
                    throw new IllegalStateException(
                            "Certificate mode contains an uncertified parent edge");
                }
                if (!assignment.isRoot()) {
                    CertificateVerifier.verify(
                            assignment.provenancePath().composedCertificate());
                }
            }
            for (Map.Entry<EClassId, CertifiedInsertionResult> entry
                    : insertionHistory.entrySet()) {
                if (!classes.containsKey(entry.getKey())
                        || !entry.getKey().equals(
                                entry.getValue().insertedClass().id())) {
                    throw new IllegalStateException(
                            "Insertion provenance names an unregistered e-class");
                }
                for (TypedEqualityCertificate certificate
                        : entry.getValue().retainedSourceProofs()) {
                    CertificateVerifier.verify(certificate);
                }
                entry.getValue().collisionEdge().ifPresent(
                        CertificateVerifier::verifyParentEdge);
            }
        }
        validateRestrictionHistory();
        if (status == GraphStatus.QUIESCENT) {
            if (!dirtyParents.isEmpty()) {
                throw new IllegalStateException(
                        "A quiescent graph cannot retain dirty parent records");
            }
            NavigableMap<CanonicalShape, NavigableSet<EClassId>> expected =
                    new TreeMap<>();
            for (TypedEClassRecord record : classes.values()) {
                if (!unionFind.isLeader(record.id())) {
                    continue;
                }
                for (CanonicalShape shape : record.shapeWitnesses().keySet()) {
                    expected.computeIfAbsent(shape, ignored -> new TreeSet<>())
                            .add(record.id());
                }
            }
            if (!expected.equals(hashCons)) {
                throw new IllegalStateException(
                        "Quiescent hash buckets do not exactly match leader-owned shapes");
            }
        }
    }

    private void validateRestrictionHistory() {
        for (Map.Entry<EClassId, List<InterfaceRestrictionCertificate>> entry
                : restrictionHistory.entrySet()) {
            TypedEClassRecord current = classes.get(entry.getKey());
            if (current == null || entry.getValue().isEmpty()) {
                throw new IllegalStateException("Malformed interface-restriction history");
            }
            TypedEClassInterface previous = entry.getValue().get(0).originalInterface();
            for (InterfaceRestrictionCertificate restriction : entry.getValue()) {
                CertificateVerifier.verifyInterfaceRestriction(restriction);
                if (!previous.equals(restriction.originalInterface())) {
                    throw new IllegalStateException(
                            "Interface-restriction history is not composable");
                }
                previous = restriction.restrictedInterface();
            }
            if (!previous.equals(current.interfaceView())) {
                throw new IllegalStateException(
                        "Interface-restriction history does not reach current metadata");
            }
        }
    }

    private void requireQuiescent() {
        if (status != GraphStatus.QUIESCENT) {
            throw new IllegalStateException(
                    "Canonical hash-cons queries require a quiescent graph");
        }
    }

    private void requireStructuralFixture(String operation) {
        if (certificateMode != GraphCertificateMode.STRUCTURAL_FIXTURE) {
            throw new IllegalStateException(
                    operation + " is available only to structural gate fixtures");
        }
    }

    private void requireCertifiedNodeTheory(TypedENode node) {
        if (certificateMode == GraphCertificateMode.REQUIRED) {
            CertificateVerifier.requireProductionNodeTheory(node, semanticProfile);
        } else if (certificateMode == GraphCertificateMode.TEST_ONLY) {
            CertificateVerifier.requireCertifiedNodeTheory(node);
        }
    }

    private boolean requiresCertificates() {
        return certificateMode != GraphCertificateMode.STRUCTURAL_FIXTURE;
    }

    boolean requiresProductionTheoryAuthority() {
        return certificateMode == GraphCertificateMode.REQUIRED;
    }

    private void requireRecord(TypedEClassInterface eclass) {
        TypedEClassRecord record = classes.get(eclass.id());
        if (record == null) {
            throw new IllegalArgumentException("Unknown e-class id: " + eclass.id());
        }
        if (!record.interfaceView().equals(eclass)) {
            throw new IllegalArgumentException(
                    "E-class metadata differs from the graph-owned record");
        }
    }

    private void validateStoredInvocations(TypedEClassRecord record) {
        for (CanonicalShape shape : record.shapeWitnesses().keySet()) {
            for (PortValue port : shape.node().ports()) {
                validatePortInvocations(port, false);
            }
        }
    }

    private void validateStoredInvocationsState(
            ParentRecordKey key,
            TypedENode node) {
        for (PortValue port : node.ports()) {
            validatePortInvocationsState(key, port);
        }
    }

    private void validatePortInvocations(PortValue port, boolean invariantCheck) {
        if (port instanceof OnePort) {
            PortLeaf leaf = ((OnePort) port).leaf();
            if (leaf instanceof InvocationPortLeaf) {
                TypedEClassInterface target = ((InvocationPortLeaf) leaf)
                        .invocation().eclass();
                if (invariantCheck) {
                    try {
                        requireRecord(target);
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalStateException(
                                "Stored shape references missing or stale e-class metadata",
                                exception);
                    }
                } else {
                    requireRecord(target);
                }
            }
            return;
        }
        if (port instanceof SeqPort) {
            for (PortValue element : ((SeqPort) port).elements()) {
                validatePortInvocations(element, invariantCheck);
            }
        } else if (port instanceof BagPort) {
            for (PortValue element : ((BagPort) port).occurrences()) {
                validatePortInvocations(element, invariantCheck);
            }
        } else if (port instanceof SetPort) {
            for (PortValue element : ((SetPort) port).elements()) {
                validatePortInvocations(element, invariantCheck);
            }
        } else if (port instanceof BindPort) {
            validatePortInvocations(((BindPort) port).body(), invariantCheck);
        } else if (port instanceof BindBlockPort) {
            validatePortInvocations(((BindBlockPort) port).body(), invariantCheck);
        } else {
            throw new IllegalStateException("Unhandled port value " + port.getClass().getName());
        }
    }

    private void validatePortInvocationsState(
            ParentRecordKey key,
            PortValue port) {
        if (port instanceof OnePort) {
            PortLeaf leaf = ((OnePort) port).leaf();
            if (leaf instanceof InvocationPortLeaf) {
                TypedInvocation invocation = ((InvocationPortLeaf) leaf).invocation();
                TypedEClassRecord target = classes.get(invocation.eclass().id());
                if (target == null) {
                    throw new IllegalStateException(
                            "Stored shape references a missing e-class");
                }
                boolean current = target.interfaceView().equals(invocation.eclass());
                boolean leader = current && unionFind.isLeader(target.id());
                if (status == GraphStatus.QUIESCENT && (!current || !leader)) {
                    throw new IllegalStateException(
                            "Quiescent stored invocation is historical or nonleader");
                }
                if ((!current || !leader)
                        && !dirtyParents.contains(key)
                        && !key.equals(rebuildingRecord)) {
                    throw new IllegalStateException(
                            "Every stale stored invocation must have a dirty parent record");
                }
                try {
                    normalizeInvocation(invocation);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException(
                            "Stored invocation lacks certified current metadata", exception);
                }
            }
            return;
        }
        if (port instanceof SeqPort) {
            for (PortValue child : ((SeqPort) port).elements()) {
                validatePortInvocationsState(key, child);
            }
        } else if (port instanceof BagPort) {
            for (PortValue child : ((BagPort) port).occurrences()) {
                validatePortInvocationsState(key, child);
            }
        } else if (port instanceof SetPort) {
            for (PortValue child : ((SetPort) port).elements()) {
                validatePortInvocationsState(key, child);
            }
        } else if (port instanceof BindPort) {
            validatePortInvocationsState(key, ((BindPort) port).body());
        } else if (port instanceof BindBlockPort) {
            validatePortInvocationsState(key, ((BindBlockPort) port).body());
        } else {
            throw new IllegalStateException("Unhandled port value " + port.getClass().getName());
        }
    }

    /** One exact-shape owner pair, canonicalized independently of orientation. */
    private static final class CollisionPairKey
            implements Comparable<CollisionPairKey> {
        private final CanonicalShape shape;
        private final EClassId first;
        private final EClassId second;

        private CollisionPairKey(
                CanonicalShape shape, EClassId left, EClassId right) {
            this.shape = Objects.requireNonNull(shape, "shape");
            EClassId checkedLeft = Objects.requireNonNull(left, "left");
            EClassId checkedRight = Objects.requireNonNull(right, "right");
            if (checkedLeft.equals(checkedRight)) {
                throw new IllegalArgumentException(
                        "A collision pair requires distinct owners");
            }
            this.first = checkedLeft.compareTo(checkedRight) < 0
                    ? checkedLeft : checkedRight;
            this.second = checkedLeft.compareTo(checkedRight) < 0
                    ? checkedRight : checkedLeft;
        }

        @Override
        public int compareTo(CollisionPairKey other) {
            int shapeOrder = shape.compareTo(other.shape);
            if (shapeOrder != 0) {
                return shapeOrder;
            }
            int firstOrder = first.compareTo(other.first);
            return firstOrder != 0 ? firstOrder : second.compareTo(other.second);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CollisionPairKey)) {
                return false;
            }
            CollisionPairKey key = (CollisionPairKey) other;
            return shape.equals(key.shape)
                    && first.equals(key.first)
                    && second.equals(key.second);
        }

        @Override
        public int hashCode() {
            return Objects.hash(shape, first, second);
        }
    }

    private static final class InvocationNormalization {
        private final TypedInvocation invocation;
        private final TypedEqualityCertificate certificate;

        private InvocationNormalization(
                TypedInvocation invocation,
                TypedEqualityCertificate certificate) {
            this.invocation = invocation;
            this.certificate = certificate;
        }
    }

    private static final class TransferredShape {
        private final ShapeWitness witness;
        private final TypedEqualityCertificate equation;

        private TransferredShape(
                ShapeWitness witness,
                TypedEqualityCertificate equation) {
            this.witness = witness;
            this.equation = equation;
        }
    }

    private static final class UnionMutation {
        private final ParentStep step;
        private final CertificateTracePayload.Union trace;

        private UnionMutation(
                ParentStep step,
                CertificateTracePayload.Union trace) {
            this.step = Objects.requireNonNull(step, "step");
            this.trace = Objects.requireNonNull(trace, "trace");
        }
    }

    private static final class RebuildStepResult {
        private final boolean changed;
        private final CertificateTracePayload.RebuildRecord trace;
        private final List<CertificateTracePayload.Union> generatedSubtransitions;

        private RebuildStepResult(
                boolean changed,
                CertificateTracePayload.RebuildRecord trace,
                List<CertificateTracePayload.Union> generatedSubtransitions) {
            this.changed = changed;
            this.trace = Objects.requireNonNull(trace, "trace");
            this.generatedSubtransitions = Collections.unmodifiableList(
                    new ArrayList<>(generatedSubtransitions));
        }

    }
}
