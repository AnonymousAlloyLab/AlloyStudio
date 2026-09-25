package is.fivefivefive.CanDis.theory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic producer bridge into the standalone {@code .acgncert} schema. */
public final class CertificateBundleWriter {
    private static final String SCHEMA_VERSION = "acgncert-schema-v10";
    private static final int FORMAT_VERSION = 1;
    private static final long DEFAULT_MAX_SERIALIZED_ORBIT_CANDIDATES = 100_000L;
    private static final int DEFAULT_MAX_SERIALIZED_ORBIT_DEPTH = 512;
    private static final String POLYMORPHIC_OPERATOR_KEY_PREFIX =
            "operator/polymorphic-key-v1/";
    private static final String CALL_OCCURRENCE_ANCHOR_PREFIX =
            "ACGN/CALL-OCCURRENCE/";
    private static final byte[] MAGIC = new byte[] {
            'A', 'C', 'G', 'N', 'C', 'E', 'R', 'T'
    };

    private CertificateBundleWriter() {
    }

    /** Writes the finite Phase-J bridge slice after validating all retained evidence. */
    public static CertificateWriteMetrics write(
            CertificateExportSession session,
            Path output)
            throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(session, "session").provenance().requirePublishable();
        session.artifact().semanticProfile().requireCertificateExportAuthority(
                session.provenance().testOnly());
        Slice slice = requireSupportedSlice(session);
        Assembler assembler = new Assembler(session, slice);
        byte[] encoded = encode(assembler.build());
        replaceAtomically(output.toAbsolutePath(), encoded);
        long inputBytes = 0L;
        long kernelBytes = 0L;
        long traceLength = 0L;
        long globalFreeRenamingCandidates = 0L;
        long localQuotientWorkItems = 0L;
        for (CertifiedInsertionResult insertion
                : session.finalSnapshot().insertions().values()) {
            CanonicalizationMetrics metrics = insertion.canonicalization()
                    .structural().metrics();
            inputBytes = Math.addExact(
                    inputBytes, metrics.inputSerializedBytes());
            kernelBytes = Math.addExact(
                    kernelBytes, metrics.kernelSerializedBytes());
            traceLength = Math.addExact(
                    traceLength, metrics.retainedTraceLength());
            globalFreeRenamingCandidates = Math.addExact(
                    globalFreeRenamingCandidates,
                    metrics.globalFreeRenamingCandidates());
            localQuotientWorkItems = Math.addExact(
                    localQuotientWorkItems,
                    metrics.localQuotientWorkItems());
        }
        return new CertificateWriteMetrics(
                inputBytes,
                kernelBytes,
                traceLength,
                globalFreeRenamingCandidates,
                localQuotientWorkItems,
                assembler.serializedCanonicalOrbitCandidates(),
                encoded.length);
    }

    private static void replaceAtomically(Path output, byte[] encoded)
            throws IOException {
        Path parent = output.getParent();
        if (parent == null) {
            throw new IOException("Output path has no parent: " + output);
        }
        Files.createDirectories(parent);
        String prefix = output.getFileName().toString() + ".";
        if (prefix.length() < 3) {
            prefix = "acgncert.";
        }
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, encoded);
            try {
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "Atomic replacement is unavailable for " + output,
                        exception);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Slice requireSupportedSlice(CertificateExportSession session)
            throws IOException {
        if (!session.containerLaws().equals(session.artifact().containerLaws())) {
            throw uncheckable(
                    "session and artifact container-law registries differ");
        }
        List<CertificateTraceEvent> events = session.events();
        if (events.isEmpty()) {
            throw uncheckable("the retained transition history is empty");
        }
        Map<EClassId, InsertionEvidence> insertions = new LinkedHashMap<>();
        Map<ParentRecordKey, InsertionEvidence> shapes = new LinkedHashMap<>();
        ParentEdgeCertificate union = null;
        CertificateTraceSnapshot prior = null;
        for (int index = 0; index < events.size(); index++) {
            CertificateTraceEvent event = events.get(index);
            if (event.sequence() != index) {
                throw uncheckable("transition sequence is not consecutive");
            }
            if (prior != null && !prior.stateKey().equals(event.before().stateKey())) {
                throw uncheckable("retained snapshots are discontinuous");
            }
            requireSupportedSnapshot(event.before());
            requireSupportedSnapshot(event.after());
            switch (event.kind()) {
                case INSERT_FRESH -> {
                    if (!(event.payload() instanceof CertificateTracePayload.Insertion)) {
                        throw uncheckable("fresh insertion payload is missing");
                    }
                    CertifiedInsertionResult insertion =
                            ((CertificateTracePayload.Insertion) event.payload()).result();
                    requireSupportedInsertion(insertion);
                    if (insertion.collided()) {
                        throw uncheckable("insert collisions are not exportable");
                    }
                    InsertionEvidence evidence = new InsertionEvidence(event, insertion);
                    if (insertions.putIfAbsent(
                            insertion.insertedClass().id(), evidence) != null) {
                        throw uncheckable("one e-class has two fresh insertion records");
                    }
                    CanonicalShape shape = insertion.canonicalization().shape();
                    ParentRecordKey key = new ParentRecordKey(
                            insertion.insertedClass().id(), shape);
                    if (shapes.putIfAbsent(key, evidence) != null) {
                        throw uncheckable(
                                "one owner has two fresh records for one canonical shape");
                    }
                }
                case UNION -> {
                    if (union != null
                            || !(event.payload() instanceof CertificateTracePayload.Union)) {
                        throw uncheckable("the supported slice has exactly one certified union");
                    }
                    union = ((CertificateTracePayload.Union) event.payload()).certificate();
                    requireDirectGroundEdge(union);
                }
                case REBUILD_START -> {
                    if (!(event.payload()
                            instanceof CertificateTracePayload.RebuildStart)
                            || !event.before().stateKey().equals(
                                    ((CertificateTracePayload.RebuildStart)
                                            event.payload()).initialStateKey())
                            || !event.before().stateKey().equals(
                                    event.after().stateKey())) {
                        throw uncheckable(
                                "rebuild start is not its exact retained boundary");
                    }
                }
                case REBUILD_COMPLETE -> {
                    if (!(event.payload()
                            instanceof CertificateTracePayload.RebuildComplete)) {
                        throw uncheckable("rebuild completion payload is missing");
                    }
                    RebuildReport report =
                            ((CertificateTracePayload.RebuildComplete) event.payload()).report();
                    if (report.processedRecords() != 0
                            || report.changedKeys() != 0
                            || report.collisions() != 0
                            || report.certifiedUnions() != 0) {
                        throw uncheckable(
                                "a rebuild requiring REBUILD_RECORD is outside this slice");
                    }
                }
                default -> throw uncheckable(
                        event.kind() + " transitions are outside the supported slice");
            }
            prior = event.after();
        }

        boolean freshOnly = events.stream().allMatch(
                event -> event.kind() == CertificateTraceEvent.Kind.INSERT_FRESH);
        boolean parentFixture = events.size() == 6
                && events.get(0).kind() == CertificateTraceEvent.Kind.INSERT_FRESH
                && events.get(1).kind() == CertificateTraceEvent.Kind.INSERT_FRESH
                && events.get(2).kind() == CertificateTraceEvent.Kind.UNION
                && events.get(3).kind() == CertificateTraceEvent.Kind.REBUILD_START
                && events.get(4).kind() == CertificateTraceEvent.Kind.REBUILD_COMPLETE
                && events.get(5).kind() == CertificateTraceEvent.Kind.INSERT_FRESH;
        if (!freshOnly && !parentFixture) {
            throw uncheckable(
                    "history is neither bottom-up fresh insertion nor the exact parent-path slice");
        }
        if (freshOnly != (union == null)) {
            throw uncheckable("union evidence does not match the retained event slice");
        }

        CertificateTraceSnapshot finalSnapshot = session.finalSnapshot();
        if (!prior.stateKey().equals(finalSnapshot.stateKey())
                || finalSnapshot.status() != GraphStatus.QUIESCENT
                || !finalSnapshot.dirtyParents().isEmpty()
                || !session.artifact().classes().equals(finalSnapshot.classes())
                || session.artifact().witnesses().graphRevision()
                        != finalSnapshot.revision()) {
            throw uncheckable("publication is not the final complete quiescent snapshot");
        }
        if (!finalSnapshot.insertions().keySet().equals(insertions.keySet())) {
            throw uncheckable("retained insertion history is incomplete");
        }
        for (Map.Entry<EClassId, CertifiedInsertionResult> entry
                : finalSnapshot.insertions().entrySet()) {
            InsertionEvidence retained = insertions.get(entry.getKey());
            if (retained == null || !retained.insertion().equals(entry.getValue())) {
                throw uncheckable("snapshot insertion provenance differs from the event log");
            }
        }

        EClassId rootId = insertion(events.get(events.size() - 1))
                .insertedClass().id();
        CertifiedInsertionResult rootInsertion = insertions.get(rootId).insertion();
        if (!session.artifact().root().equals(rootInsertion.returnedInvocation())) {
            throw uncheckable("published root is not the final fresh insertion");
        }
        if (session.artifact().unfoldings().size() != 1) {
            throw uncheckable("exactly one complete root unfolding is required");
        }
        FiniteUnfoldingTree unfolding = session.artifact().unfoldings().get(0);
        requireSupportedUnfolding(unfolding, rootInsertion);

        if (parentFixture) {
            requireParentFixture(events, insertions, union, rootInsertion);
        }
        return new Slice(
                List.copyOf(events),
                Map.copyOf(insertions),
                Map.copyOf(shapes),
                union,
                rootInsertion,
                unfolding);
    }

    private static void requireParentFixture(
            List<CertificateTraceEvent> events,
            Map<EClassId, InsertionEvidence> insertions,
            ParentEdgeCertificate union,
            CertifiedInsertionResult root) throws IOException {
        if (union == null || insertions.size() != 3) {
            throw uncheckable("parent-path slice requires two leaves and one wrapper");
        }
        CertifiedInsertionResult first = insertion(events.get(0));
        CertifiedInsertionResult second = insertion(events.get(1));
        if (!union.child().equals(second.insertedClass())
                || !union.parent().equals(first.insertedClass())
                || !isIdentity(union.embedding())) {
            throw uncheckable("the retained union is not the direct second-to-first edge");
        }
        if (!onlySlotPorts(first.canonicalization().structural().source())
                || !onlySlotPorts(second.canonicalization().structural().source())) {
            throw uncheckable("parent-path leaves must be ONE_SLOT applications");
        }
        List<TypedFindResult> finds = root.canonicalization().structural()
                .xi().findResults();
        if (finds.size() != 1
                || finds.get(0).parentPath().steps().size() != 1
                || !finds.get(0).parentPath().steps().get(0).certificate().equals(union)
                || !finds.get(0).originalInvocation().eclass()
                        .equals(second.insertedClass())
                || !finds.get(0).leaderInvocation().eclass()
                        .equals(first.insertedClass())) {
            throw uncheckable("wrapper does not retain the required nonidentity parent path");
        }
        TypedENode source = root.canonicalization().structural().source();
        if (source.ports().size() != 1
                || !(source.ports().get(0) instanceof OnePort)
                || !(((OnePort) source.ports().get(0)).leaf()
                        instanceof InvocationPortLeaf)) {
            throw uncheckable("wrapper must contain one ONE_TERM invocation");
        }
    }

    private static CertifiedInsertionResult insertion(CertificateTraceEvent event) {
        return ((CertificateTracePayload.Insertion) event.payload()).result();
    }

    private static void requireSupportedSnapshot(CertificateTraceSnapshot snapshot)
            throws IOException {
        if (!snapshot.retiredShapeRecords().isEmpty()) {
            throw uncheckable("retired collision records are not exportable");
        }
        if (!snapshot.restrictions().isEmpty()) {
            throw uncheckable("interface restrictions are not exportable");
        }
        for (TypedEClassRecord record : snapshot.classes().values()) {
            requireBoundedContext(record.exposedSlots());
            if (!record.symmetryGroup().generators().isEmpty()) {
                throw uncheckable("nontrivial e-class symmetries are not exportable");
            }
            for (Map.Entry<CanonicalShape, ShapeWitness> stored
                    : record.shapeWitnesses().entrySet()) {
                requireBoundedContext(stored.getKey().exactSlots());
                ShapeWitness witness = stored.getValue();
                if (!stored.getKey().exactSlots().equals(witness.exactSlots())
                        || !record.exposedSlots().equals(witness.ambientSupport())
                        || !witness.ambientSupport().equals(witness.exposedInterface())
                        || (!isIdentity(witness.instantiatingRenaming())
                                && !onlySlotPorts(stored.getKey().node()))) {
                    throw uncheckable(
                            "redundant shape coordinates are outside the supported slice");
                }
                requireSupportedNode(stored.getKey().node());
            }
        }
        for (ParentAssignment assignment : snapshot.parents().values()) {
            if (!assignment.isRoot()) {
                ParentPath path = assignment.provenancePath();
                if (path.steps().size() != 1 || !path.hasCertificates()) {
                    throw uncheckable("only one retained primitive parent edge is exportable");
                }
                requireDirectGroundEdge(path.steps().get(0).certificate());
            }
        }
    }

    private static void requireSupportedInsertion(CertifiedInsertionResult insertion)
            throws IOException {
        CanonicalizationResult result = insertion.canonicalization().structural();
        TypedENode source = result.source();
        TypedENode kernel = result.kernel();
        requireSupportedNode(source);
        requireSupportedNode(kernel);
        requireBoundedContext(source.context());
        if (!source.context().equals(source.support())
                || !source.context().equals(result.effectiveSupport())
                || !source.context().equals(kernel.context())
                || !source.context().equals(insertion.insertedClass().exposedSlots())
                || !result.shape().node().act(result.sigma()).equals(kernel)
                || !isIdentity(result.inclusion())
                || !result.omega().equals(result.sigma().andThen(result.inclusion()))
                || !insertion.shapeWitness().instantiatingRenaming().equals(
                        result.sigma())
                || (!isIdentity(result.sigma())
                        && (!onlySlotPorts(result.shape().node())
                                || !onlySlotPorts(kernel)
                                || !result.shape().node().context().equals(
                                        result.effectiveSupport())))) {
            throw uncheckable(
                    "support contraction or nonidentity iota/sigma/omega is not exportable");
        }
        if (!insertion.returnedInvocation().eclass().equals(
                    insertion.insertedClass())
                || !isIdentity(insertion.returnedInvocation().embedding())) {
            throw uncheckable("fresh insertion does not return its identity invocation");
        }
        for (TypedFindResult find : result.xi().findResults()) {
            if (!find.originalInvocation().equals(find.normalizedInvocation())
                    || !find.parentPath().hasCertificates()) {
                throw uncheckable("invocation normalization lacks an exact retained path");
            }
            for (ParentStep step : find.parentPath().steps()) {
                requireDirectGroundEdge(step.certificate());
            }
        }
    }

    private static void requireSupportedNode(TypedENode node) throws IOException {
        for (PortValue port : node.ports()) {
            requireSupportedPort(port);
        }
    }

    private static void requireSupportedPort(PortValue port) throws IOException {
        if (port instanceof OnePort) {
            OnePort one = (OnePort) port;
            if (!(one.leaf() instanceof SlotPortLeaf)
                    && !(one.leaf() instanceof InvocationPortLeaf)) {
                throw uncheckable("unsupported ONE port leaf");
            }
            return;
        }
        if (port instanceof SeqPort) {
            for (PortValue element : ((SeqPort) port).elements()) {
                requireSupportedPort(element);
            }
            return;
        }
        if (port instanceof BagPort) {
            for (PortValue element : ((BagPort) port).occurrences()) {
                requireSupportedPort(element);
            }
            return;
        }
        if (port instanceof SetPort) {
            for (PortValue element : ((SetPort) port).elements()) {
                requireSupportedPort(element);
            }
            return;
        }
        if (port instanceof BindPort) {
            requireSupportedPort(((BindPort) port).body());
            return;
        }
        if (port instanceof BindBlockPort) {
            requireSupportedPort(((BindBlockPort) port).body());
            return;
        }
        throw uncheckable("unsupported port value " + port.getClass().getName());
    }

    private static boolean onlySlotPorts(TypedENode node) {
        for (PortValue port : node.ports()) {
            if (!(port instanceof OnePort)
                    || !(((OnePort) port).leaf() instanceof SlotPortLeaf)) {
                return false;
            }
        }
        return !node.ports().isEmpty();
    }

    private static void requireBoundedContext(TypedSlotContext context)
            throws IOException {
        long candidates = 1L;
        for (int count : context.typeCounts().values()) {
            for (int factor = 2; factor <= count; factor++) {
                try {
                    candidates = Math.multiplyExact(candidates, factor);
                } catch (ArithmeticException exception) {
                    throw uncheckable("typed free-renaming count overflows its bound");
                }
                if (candidates > TypedRenamingEnumerator.maximumRenamings()) {
                    throw uncheckable(
                            "typed free-renaming orbit exceeds configured bound "
                                    + TypedRenamingEnumerator.maximumRenamings());
                }
            }
        }
    }

    private static void requireDirectGroundEdge(ParentEdgeCertificate edge)
            throws IOException {
        TypedEqualityCertificate derivation = edge.endpointDerivation();
        if (derivation.category() == CertificateCategory.EQUATIONAL_SYMMETRY
                && derivation.premises().size() == 1) {
            derivation = derivation.premises().get(0);
        }
        if (!(derivation instanceof InputEquationCertificate)
                || !derivation.premises().isEmpty()
                || (derivation.category() != CertificateCategory.INPUT_EQUATION
                        && derivation.category() != CertificateCategory.REWRITE_AXIOM)) {
            throw uncheckable(
                    "parent edge is not a direct ground input equation or rewrite");
        }
        if (!edge.child().exposedSlots().equals(edge.parentInvocation().callerContext())) {
            throw uncheckable("parent edge endpoints do not share one ground context");
        }
    }

    private static void requireSupportedUnfolding(
            FiniteUnfoldingTree tree,
            CertifiedInsertionResult root) throws IOException {
        if (!tree.rootInvocation().equals(root.returnedInvocation())) {
            throw uncheckable("finite unfolding belongs to another root");
        }
        if (tree.height() <= 0) {
            throw uncheckable("finite unfolding has no root level");
        }
        requireUnfoldingNode(tree, new HashSet<>());
        FiniteUnfoldingIndexTrace trace = tree.indexTrace();
        if (!trace.finalContext().equals(tree.rootInvocation().callerContext())
                || !isIdentity(trace.finalWeakening())
                || trace.steps().size() != unfoldingNodeCount(tree)) {
            throw uncheckable("unfolding index trace is not the retained rigid trace");
        }
    }

    private static int unfoldingNodeCount(FiniteUnfoldingTree tree) {
        int count = 1;
        for (FiniteUnfoldingTree child : tree.invocationChildren()) {
            count = Math.addExact(count, unfoldingNodeCount(child));
        }
        return count;
    }

    private static void requireUnfoldingNode(
            FiniteUnfoldingTree tree,
            Set<String> active) throws IOException {
        String key = tree.rootInvocation().toString() + "\u0000"
                + tree.selectedShape().structuralKey().stableString();
        if (!active.add(key)) {
            throw uncheckable("cyclic unfoldings are not exportable");
        }
        try {
            ShapeWitness witness = tree.shapeWitness();
            if (!witness.ambientSupport().equals(witness.exposedInterface())) {
                throw uncheckable("redundant unfolding coordinates are not exportable");
            }
            for (FiniteUnfoldingTree child : tree.invocationChildren()) {
                requireUnfoldingNode(child, active);
            }
        } finally {
            active.remove(key);
        }
    }

    private static boolean isIdentity(TypedEmbedding embedding) {
        if (!embedding.source().equals(embedding.codomain())) {
            return false;
        }
        for (TypedSlot slot : embedding.source()) {
            if (!slot.equals(embedding.mapping().get(slot))) {
                return false;
            }
        }
        return true;
    }

    private static IOException uncheckable(String detail) {
        return new IOException("UNCHECKABLE: " + detail);
    }

    private static final class Assembler {
        private final CertificateExportSession session;
        private final Slice slice;
        private final Tables tables = new Tables(this::type);
        private final Map<EClassId, String> witnessIds = new LinkedHashMap<>();
        private final Map<EClassId, InsertionWire> insertionWires = new LinkedHashMap<>();
        private final Map<ParentRecordKey, InsertionWire> shapeWires =
                new LinkedHashMap<>();
        private final Map<StructuralKey, Node> snapshots = new LinkedHashMap<>();
        private final Map<String, Node> schemas = new TreeMap<>();
        private final Map<String, Node> operators = new TreeMap<>();
        private final Map<String, Node> binders = new TreeMap<>();
        private final Map<String, Node> axioms = new TreeMap<>();
        private final Map<String, Node> edgeProofs = new LinkedHashMap<>();
        private final Map<String, Node> exactTypes = new TreeMap<>();
        private long serializedCanonicalOrbitCandidates;

        private Assembler(CertificateExportSession session, Slice slice) {
            this.session = session;
            this.slice = slice;
            for (InsertionEvidence evidence : slice.insertions().values()) {
                EClassId id = evidence.insertion().insertedClass().id();
                witnessIds.put(id, "w/" + id + "@" + evidence.event().after().revision());
            }
        }

        private Node build() throws IOException {
            for (CertificateTraceEvent event : slice.events()) {
                if (event.kind() == CertificateTraceEvent.Kind.INSERT_FRESH) {
                    buildReplay(insertion(event));
                }
            }
            for (InsertionEvidence evidence : slice.insertions().values()) {
                InsertionWire wire = insertionWires.get(
                        evidence.insertion().insertedClass().id());
                tables.witness(leaf(
                        "witness",
                        wire.witnessId,
                        Long.toString(evidence.event().after().revision()),
                        wire.eclassId,
                        scalar(context(evidence.insertion().insertedClass()
                                .exposedSlots()), 0),
                        type(evidence.insertion().insertedClass().outputType()),
                        scalar(wire.kernel, 0)));
            }
            for (CertificateTraceEvent event : slice.events()) {
                snapshot(event.before());
                snapshot(event.after());
            }
            Node finalSnapshot = snapshot(session.finalSnapshot());
            for (InsertionWire wire : insertionWires.values()) {
                finishInsertion(wire, finalSnapshot);
            }
            buildEvents();
            Node unfolding = buildUnfolding(finalSnapshot);
            Node theory = theory();
            String theoryDigest = contentId(theory);
            Node vocabulary = vocabulary();
            String vocabularyDigest = contentId(vocabulary);
            Node publication = publication(
                    finalSnapshot, unfolding, theoryDigest);
            List<String> runIdentity = new ArrayList<>(
                    session.provenance().identityScalars());
            runIdentity.add(session.componentVersions());
            runIdentity.add(session.finalSnapshot().stateKey().stableString());
            runIdentity.add(session.canonicalObservation().stableString());
            runIdentity.add(theoryDigest);
            runIdentity.add(vocabularyDigest);
            String runId = contentId(node(
                    "producer-run",
                    runIdentity,
                    List.of()));
            return node(
                    "acgncert-bundle",
                    List.of(SCHEMA_VERSION),
                    List.of(
                            node(
                                    "metadata",
                                    session.provenance().metadataScalars(
                                            session.componentVersions(), runId),
                                    List.of()),
                            node(
                                    "manifest",
                                    List.of(theoryDigest, vocabularyDigest),
                                    List.of(theory, vocabulary)),
                            tables.section("contexts", tables.contexts),
                            tables.section("embeddings", tables.embeddings),
                            tables.section("terms", tables.terms),
                            tables.section("proofs", tables.proofs),
                            tables.section("witnesses", tables.witnesses),
                            tables.section("snapshots", tables.snapshots),
                            node("events", tables.events),
                            tables.section(
                                    "canonical-records", tables.canonicalRecords),
                            tables.section("unfoldings", tables.unfoldings),
                            publication));
        }

        private void buildReplay(CertifiedInsertionResult insertion)
                throws IOException {
            CanonicalizationResult result = insertion.canonicalization().structural();
            boolean normalizeFreeOrbit = !isIdentity(result.witness());
            TypedENode replaySource = normalizeFreeOrbit
                    ? result.shape().node() : result.source();
            TypedENode replayNormalized = normalizeFreeOrbit
                    ? result.shape().node()
                    : result.leaderKernel().ambientLeaderNode();
            TypedENode replayKernel = normalizeFreeOrbit
                    ? result.shape().node() : result.kernel();
            Node source = term(replaySource);
            Node normalized = term(replayNormalized);
            Node kernel = term(replayKernel);
            Node gamma = context(replaySource.context());
            Node delta = context(result.effectiveSupport());
            Node iota = embedding(result.inclusion());
            TypedRenaming replaySigma = TypedRenaming.identity(
                    replayKernel.context());
            Node sigma = embedding(replaySigma);
            Node omega = embedding(replaySigma.andThen(result.inclusion()));

            List<PathWire> paths = normalizeFreeOrbit
                    ? List.of() : parentPaths(result);
            List<Node> pathRecords = new ArrayList<>();
            for (PathWire path : paths) {
                List<Node> edges = path.edgeProofs.stream()
                        .map(proof -> leaf("edge-ref", scalar(proof, 0))).toList();
                pathRecords.add(node(
                        "parent-path",
                        List.of(
                                encodePath(path.path),
                                path.initialWitness,
                                path.leaderWitness,
                                scalar(path.finalInvocation, 0)),
                        edges));
            }
            List<Node> premises = new ArrayList<>();
            for (int index = paths.size() - 1; index >= 0; index--) {
                premises.addAll(paths.get(index).edgeProofs);
            }
            List<ContainerNormalizationWire> normalizations =
                    containerNormalizations(normalized);
            List<Node> normalizationRecords = new ArrayList<>();
            for (ContainerNormalizationWire normalization : normalizations) {
                premises.add(normalization.proof());
                normalizationRecords.add(leaf(
                        "port-normalization",
                        encodePath(normalization.path()),
                        scalar(normalization.proof(), 0)));
            }
            Node structural = normalized.equals(kernel)
                    ? proof(
                            "REFL",
                            gamma,
                            "TERM",
                            type(result.source().outputType()),
                            normalized,
                            normalized,
                            List.of(),
                            leaf("refl", scalar(normalized, 0)))
                    : proof(
                            "STRUCTURAL_ALPHA",
                            gamma,
                            "TERM",
                            type(result.source().outputType()),
                            normalized,
                            kernel,
                            List.of(),
                            leaf(
                                    "structural-alpha",
                                    scalar(normalized, 0),
                                    scalar(kernel, 0)));
            premises.add(structural);
            Node sourceConstruction = sourceConstructionReference(
                    insertion, normalizeFreeOrbit);
            Node replay = proof(
                    "KERNEL_REPLAY",
                    gamma,
                    "TERM",
                    type(result.source().outputType()),
                    source,
                    normalized,
                    premises,
                    node(
                            "kernel-replay",
                            List.of(
                                    scalar(source, 0),
                                    scalar(gamma, 0),
                                    scalar(kernel, 0),
                                    scalar(delta, 0),
                                    scalar(iota, 0),
                                    scalar(sigma, 0),
                                    scalar(omega, 0)),
                            List.of(
                                    node("parent-paths", pathRecords),
                                    node("port-normalizations", normalizationRecords),
                                    leaf("structural-proof", scalar(structural, 0)),
                                    node(
                                            "effective-support",
                                            result.effectiveSupport().slots().stream()
                                                    .sorted(Comparator.comparing(
                                                            this::slotOrder))
                                                    .map(Assembler::slotName)
                                                    .toList(),
                                            List.of()),
                                    sourceConstruction)));
            emitLiftedCongruence(result, source, normalized, paths);
            InsertionEvidence evidence = slice.insertions().get(
                    insertion.insertedClass().id());
            ParentRecordKey recordKey = new ParentRecordKey(
                    insertion.insertedClass().id(), result.shape());
            String occurrenceShapeId = shapeId(
                    insertion.insertedClass().id(), kernel);
            InsertionWire wire = new InsertionWire(
                    insertion,
                    witnessIds.get(insertion.insertedClass().id()),
                    insertion.insertedClass().id().toString(),
                    occurrenceShapeId,
                    source,
                    kernel,
                    replay,
                    evidence);
            insertionWires.put(insertion.insertedClass().id(), wire);
            if (shapeWires.putIfAbsent(recordKey, wire) != null) {
                throw uncheckable("duplicate owner-qualified insertion record");
            }
        }

        private List<ContainerNormalizationWire> containerNormalizations(
                Node replayed) throws IOException {
            List<TermAtPath> occurrences = new ArrayList<>();
            collectContainerTerms(
                    replayed, new ArrayList<>(), null, null, occurrences);
            occurrences.sort((left, right) -> comparePaths(left.path(), right.path()));
            List<ContainerNormalizationWire> result = new ArrayList<>();
            for (TermAtPath occurrence : occurrences) {
                Node container = occurrence.term();
                String kind = container.scalars.get(1);
                Node termContext = tables.contexts.get(container.scalars.get(2));
                if (termContext == null) {
                    throw uncheckable(
                            "container normalization references an absent context");
                }
                List<Node> childProofs = new ArrayList<>();
                List<Node> childOccurrences = new ArrayList<>();
                for (int index = 0; index < container.children.size(); index++) {
                    Node child = tables.terms.get(scalar(container.children.get(index), 0));
                    if (child == null) {
                        throw uncheckable(
                                "container normalization references an absent child term");
                    }
                    Node childProof = proof(
                            "REFL",
                            termContext,
                            child.scalars.get(3),
                            child.scalars.get(4),
                            child,
                            child,
                            List.of(),
                            leaf("refl", scalar(child, 0)));
                    childProofs.add(childProof);
                    childOccurrences.add(leaf(
                            "occurrence",
                            Integer.toString(index),
                            scalar(childProof, 0)));
                }
                Node normalization = proof(
                        "CONTAINER_NORMALIZE",
                        termContext,
                        container.scalars.get(3),
                        container.scalars.get(4),
                        container,
                        container,
                        childProofs,
                        node(
                                "container-normalization",
                                List.of(
                                        kind,
                                        scalar(container, 0),
                                        scalar(container, 0),
                                        occurrence.operatorId(),
                                        occurrence.schemaPath()),
                                childOccurrences));
                result.add(new ContainerNormalizationWire(
                        occurrence.path(), normalization));
            }
            return List.copyOf(result);
        }

        private void collectContainerTerms(
                Node term,
                List<Integer> path,
                String enclosingOperator,
                String schemaPath,
                List<TermAtPath> result) throws IOException {
            if (!"term".equals(term.tag) || term.scalars.size() < 6) {
                throw uncheckable("container traversal received a malformed term");
            }
            String kind = term.scalars.get(1);
            if (kind.equals("SEQ") || kind.equals("BAG") || kind.equals("SET")) {
                if (enclosingOperator == null || schemaPath == null) {
                    throw uncheckable(
                            "container normalization lacks an enclosing operator path");
                }
                result.add(new TermAtPath(
                        List.copyOf(path), term, enclosingOperator, schemaPath));
            }
            for (int index = 0; index < term.children.size(); index++) {
                Node child = tables.terms.get(scalar(term.children.get(index), 0));
                if (child == null) {
                    throw uncheckable("container traversal has a dangling child term");
                }
                List<Integer> childPath = new ArrayList<>(path);
                childPath.add(index);
                String childOperator = enclosingOperator;
                String childSchemaPath = schemaPath;
                if (kind.equals("APP")) {
                    childOperator = term.scalars.get(5);
                    childSchemaPath = index + "/0";
                } else if (childSchemaPath != null) {
                    int slash = childSchemaPath.indexOf('/');
                    int depth = Integer.parseInt(
                            childSchemaPath.substring(slash + 1));
                    childSchemaPath = childSchemaPath.substring(0, slash + 1)
                            + (depth + 1);
                }
                collectContainerTerms(
                        child, childPath, childOperator, childSchemaPath, result);
            }
        }

        private Node sourceConstructionReference(
                CertifiedInsertionResult insertion,
                boolean producerOrbitMarker) throws IOException {
            CanonicalizationResult result = insertion.canonicalization().structural();
            if (producerOrbitMarker) {
                if (insertion.canonicalization().sourceConstruction().isPresent()) {
                    throw uncheckable(
                            "nonidentity free renaming with source-construction evidence "
                                    + "is outside the bounded export slice");
                }
                return leaf(
                        "source-construction",
                        "NONE",
                        "producer-orbit-source-v1",
                        scalar(term(result.kernel()), 0),
                        scalar(embedding(result.witness()), 0));
            }
            java.util.Optional<TypedEqualityCertificate> source = insertion
                    .canonicalization().sourceConstruction();
            if (source.isEmpty()) {
                return leaf("source-construction", "NONE", "", "", "");
            }
            TypedEqualityCertificate certificate = source.get();
            String kind;
            if (certificate instanceof FlatConstructionCertificate) {
                kind = "FLAT";
            } else if (certificate instanceof ContainerConstructionCertificate) {
                kind = "CONTAINER";
            } else if (certificate instanceof DependentChainCertificate) {
                kind = "CHAIN";
            } else {
                throw new IllegalStateException(
                        "Unsupported source-construction certificate "
                                + certificate.getClass().getName());
            }
            return leaf(
                    "source-construction",
                    kind,
                    certificate.structuralKey().stableString(),
                    certificate.leftEndpoint().structuralKey().stableString(),
                    constructionSourceOwner(certificate));
        }

        private String constructionSourceOwner(
                TypedEqualityCertificate certificate) {
            InsertionEvidence selected = null;
            for (InsertionEvidence evidence : slice.insertions().values()) {
                java.util.Optional<TypedEqualityCertificate> source = evidence
                        .insertion().canonicalization().sourceConstruction();
                if (source.isEmpty()
                        || !source.get().structuralKey().equals(
                                certificate.structuralKey())) {
                    continue;
                }
                if (selected != null) {
                    throw new IllegalStateException(
                            "One construction certificate has ambiguous source owners");
                }
                selected = evidence;
            }
            if (selected == null) {
                throw new IllegalStateException(
                        "Construction evidence has no retained insertion owner");
            }
            return "source-owner/" + contentId(node(
                    "construction-source-owner-v1",
                    List.of(
                            session.provenance().inputIdentifier(),
                            session.provenance().inputSha256(),
                            Long.toString(selected.event().sequence()),
                            selected.insertion().insertedClass().id().toString()),
                    List.of()));
        }

        private static int comparePaths(List<Integer> left, List<Integer> right) {
            int shared = Math.min(left.size(), right.size());
            for (int index = 0; index < shared; index++) {
                int compared = Integer.compare(left.get(index), right.get(index));
                if (compared != 0) {
                    return compared;
                }
            }
            return Integer.compare(left.size(), right.size());
        }

        private void emitLiftedCongruence(
                CanonicalizationResult result,
                Node source,
                Node normalized,
                List<PathWire> paths) throws IOException {
            if (source.equals(normalized)) {
                return;
            }
            List<Node> changedPorts = new ArrayList<>();
            int pathIndex = 0;
            for (LeaderPortTrace trace : result.xi().portTraces()) {
                if (trace.sourcePort().equals(trace.normalizedPort())) {
                    continue;
                }
                if (trace.kind() != LeaderPortTrace.Kind.INVOCATION
                        || pathIndex >= paths.size()
                        || paths.get(pathIndex).edgeProofs.size() != 1) {
                    throw uncheckable("changed port cannot be lifted in the exact ONE slice");
                }
                Node left = term(trace.sourcePort());
                Node right = term(trace.normalizedPort());
                Node edge = paths.get(pathIndex).edgeProofs.get(0);
                changedPorts.add(proof(
                        "CONGRUENCE",
                        context(trace.sourcePort().context()),
                        "PORT",
                        schemaId(trace.sourcePort().schema()),
                        left,
                        right,
                        List.of(edge),
                        leaf("congruence", scalar(left, 0), scalar(right, 0))));
                pathIndex++;
            }
            Node app = proof(
                    "CONGRUENCE",
                    context(result.source().context()),
                    "TERM",
                    type(result.source().outputType()),
                    source,
                    normalized,
                    changedPorts,
                    leaf("congruence", scalar(source, 0), scalar(normalized, 0)));
            Node reflexive = proof(
                    "REFL",
                    context(result.source().context()),
                    "TERM",
                    type(result.source().outputType()),
                    normalized,
                    normalized,
                    List.of(),
                    leaf("refl", scalar(normalized, 0)));
            proof(
                    "TRANS",
                    context(result.source().context()),
                    "TERM",
                    type(result.source().outputType()),
                    source,
                    normalized,
                    List.of(app, reflexive),
                    node("trans", List.of()));
        }

        private List<PathWire> parentPaths(CanonicalizationResult result)
                throws IOException {
            List<InvocationOccurrence> sourceOccurrences =
                    sourceInvocationOccurrences(result.source());
            List<InvocationOccurrence> wireOccurrences =
                    wireInvocationOccurrences(result.source());
            List<TypedFindResult> finds = result.xi().findResults();
            if (sourceOccurrences.size() != finds.size()) {
                throw uncheckable("leader trace does not cover every invocation occurrence");
            }
            for (int index = 0; index < finds.size(); index++) {
                if (!sourceOccurrences.get(index).invocation().equals(
                        finds.get(index).originalInvocation())) {
                    throw uncheckable(
                            "leader trace order differs from source occurrence order");
                }
            }
            List<TypedFindResult> wireFinds = reorderOccurrenceValues(
                    sourceOccurrences, finds, wireOccurrences);
            List<PathWire> paths = new ArrayList<>();
            for (int index = 0; index < wireFinds.size(); index++) {
                TypedFindResult find = wireFinds.get(index);
                List<Node> edgeNodes = new ArrayList<>();
                for (ParentStep step : find.parentPath().steps()) {
                    edgeNodes.add(parentEdgeProof(step.certificate()));
                }
                paths.add(new PathWire(
                        wireOccurrences.get(index).path(),
                        witnessId(find.originalInvocation().eclass()),
                        witnessId(find.leaderInvocation().eclass()),
                        term(find.leaderInvocation()),
                        List.copyOf(edgeNodes)));
            }
            return List.copyOf(paths);
        }

        private void finishInsertion(InsertionWire wire, Node finalSnapshot)
                throws IOException {
            CanonicalizationResult result = wire.insertion
                    .canonicalization().structural();
            if (!isIdentity(result.witness())) {
                OrbitSummary producerMinimum = producerOrbitSummary(
                        result.shape().node(),
                        result.kernel(),
                        result.effectiveSupport());
                if (!producerMinimum.minimum().equals(result.kernel())
                        || !producerMinimum.minimumWitness().equals(result.witness())) {
                    throw uncheckable(
                            "producer and writer complete (shape,witness) minima disagree");
                }
            }
            TypedRenaming wireWitness = TypedRenaming.identity(
                    result.shape().node().context());
            Node orbitBase = term(result.shape().node());
            Node selectedWitness = embedding(wireWitness);
            Node orbitSource = orbitBase;
            Node representative = orbitBase;
            if (!orbitSource.equals(representative)) {
                throw uncheckable("supported canonical orbit is not rigid identity");
            }
            OrbitSummary orbitSummary = orbitSummary(
                    result.shape().node(), result.shape().node().context());
            serializedCanonicalOrbitCandidates = Math.addExact(
                    serializedCanonicalOrbitCandidates,
                    orbitSummary.candidateCount());
            Node orbitMinimum = term(orbitSummary.minimum());
            if (!orbitMinimum.equals(representative)
                    || !orbitSummary.minimumWitness().equals(wireWitness)) {
                throw uncheckable(
                        "producer and serialized complete-orbit minima disagree");
            }
            List<Node> groups = new ArrayList<>();
            for (InvocationOccurrence occurrence : wireInvocationOccurrences(
                    result.shape().node())) {
                groups.add(node(
                        "leader-group",
                        List.of(
                                encodePath(occurrence.path),
                                witnessId(occurrence.invocation.eclass())),
                        List.of()));
            }
            Node orbit = proof(
                    "CANONICAL_ORBIT",
                    context(result.effectiveSupport()),
                    "TERM",
                    type(result.kernel().outputType()),
                    orbitSource,
                    representative,
                    List.of(),
                    node(
                            "canonical-orbit",
                            List.of(
                                    scalar(orbitSource, 0),
                                    scalar(orbitBase, 0),
                                    scalar(context(result.effectiveSupport()), 0),
                                    scalar(representative, 0),
                                    scalar(selectedWitness, 0),
                                    Long.toString(orbitSummary.candidateCount())),
                            List.of(
                                    node(
                                            "free-renamings",
                                            orbitSummary.freeRenamingReferences()),
                                    node(
                                            "leader-groups",
                                            List.of(scalar(finalSnapshot, 0), "complete"),
                                            groups),
                                    node(
                                            "orbit-minimum",
                                            List.of(
                                                    leaf(
                                                            "term-ref",
                                                            scalar(orbitMinimum, 0)),
                                                    leaf(
                                                            "embedding-ref",
                                                            scalar(selectedWitness, 0)))),
                                    node(
                                            "binder-occurrence-refs",
                                            BinderOccurrenceProofs.collect(
                                                    result.shape().node()).stream()
                                                    .map(certificate -> leaf(
                                                            "binder-occurrence-ref",
                                                            certificate.structuralKey()
                                                                    .stableString()))
                                                    .sorted(Comparator.comparing(
                                                            value -> scalar(value, 0)))
                                                    .toList()))));
            Node fresh = proof(
                    "FRESH_WITNESS",
                    context(result.effectiveSupport()),
                    "TERM",
                    type(result.kernel().outputType()),
                    representative,
                    representative,
                    List.of(wire.replay),
                    leaf(
                            "fresh-witness",
                            wire.witnessId,
                            scalar(representative, 0),
                            scalar(embedding(result.inclusion()), 0),
                            scalar(wire.replay, 0)));
            Node canonical = tables.canonical(withContentId(
                    "canonical-record",
                    List.of(scalar(orbit, 0), scalar(representative, 0)),
                    List.of(leaf("source-replay-ref", scalar(wire.replay, 0)))));
            wire.orbit = orbit;
            wire.fresh = fresh;
            wire.canonical = canonical;
        }

        private long serializedCanonicalOrbitCandidates() {
            return serializedCanonicalOrbitCandidates;
        }

        private OrbitSummary orbitSummary(
                TypedENode base,
                TypedSlotContext targetContext)
                throws IOException {
            LeastOption<SerializedOrbitCandidate> minimum = new LeastOption<>(
                    SerializedOrbitCandidate::compare);
            Map<String, Node> freeReferences = new TreeMap<>();
            TypedRenamingEnumerator.forEachChecked(
                    base.context(), targetContext, witness -> {
                Node encodedWitness = embedding(witness);
                embedding(witness.inverse());
                String witnessId = scalar(encodedWitness, 0);
                Node reference = leaf("embedding-ref", witnessId);
                Node prior = freeReferences.putIfAbsent(witnessId, reference);
                if (prior != null && !prior.equals(reference)) {
                    throw uncheckable("free-renaming content ID collision");
                }
                TypedENode globallyRenamed = base.act(witness);
                forEachBinderNodeAlternative(globallyRenamed, candidate -> {
                    if (minimum.considered() >= maximumSerializedOrbitCandidates()) {
                        throw uncheckable(
                                "serialized canonical orbit exceeds configured bound "
                                        + maximumSerializedOrbitCandidates());
                    }
                    minimum.consider(new SerializedOrbitCandidate(
                            candidate,
                            witness,
                            CanonicalPermutationPresentation.orbitCandidateOrder(
                                    CanonicalShape.of(candidate), witness)));
                });
            });
            SerializedOrbitCandidate selected = minimum.orElseThrow(
                    () -> new IllegalStateException(
                            "A complete canonical orbit must contain a candidate"));
            return new OrbitSummary(
                    selected.node(),
                    selected.witness(),
                    minimum.considered(),
                    List.copyOf(freeReferences.values()));
        }

        private OrbitSummary producerOrbitSummary(
                TypedENode canonicalBase,
                TypedENode source,
                TypedSlotContext targetContext)
                throws IOException {
            if (!onlySlotPorts(canonicalBase) || !onlySlotPorts(source)) {
                throw uncheckable(
                        "nonidentity free renaming requires the rigid slot-only orbit slice");
            }
            LeastOption<SerializedOrbitCandidate> minimum = new LeastOption<>(
                    SerializedOrbitCandidate::compare);
            Map<String, Node> freeReferences = new TreeMap<>();
            TypedRenamingEnumerator.forEachChecked(
                    canonicalBase.context(), targetContext, witness -> {
                Node encodedWitness = embedding(witness);
                embedding(witness.inverse());
                String witnessId = scalar(encodedWitness, 0);
                Node reference = leaf("embedding-ref", witnessId);
                Node prior = freeReferences.putIfAbsent(witnessId, reference);
                if (prior != null && !prior.equals(reference)) {
                    throw uncheckable("free-renaming content ID collision");
                }
                if (minimum.considered() >= maximumSerializedOrbitCandidates()) {
                    throw uncheckable(
                            "serialized canonical orbit exceeds configured bound "
                                    + maximumSerializedOrbitCandidates());
                }
                TypedENode producerCandidate = source.act(witness.inverse());
                minimum.consider(new SerializedOrbitCandidate(
                        canonicalBase.act(witness),
                        witness,
                        CanonicalPermutationPresentation.orbitCandidateOrder(
                                CanonicalShape.of(producerCandidate), witness)));
            });
            SerializedOrbitCandidate selected = minimum.orElseThrow(
                    () -> new IllegalStateException(
                            "A complete canonical orbit must contain a candidate"));
            return new OrbitSummary(
                    selected.node(),
                    selected.witness(),
                    minimum.considered(),
                    List.copyOf(freeReferences.values()));
        }

        private static long maximumSerializedOrbitCandidates() {
            long maximum = Long.getLong(
                    "acgn.maxSerializedOrbitCandidates",
                    DEFAULT_MAX_SERIALIZED_ORBIT_CANDIDATES);
            if (maximum <= 0) {
                throw new IllegalStateException(
                        "acgn.maxSerializedOrbitCandidates must be positive");
            }
            return maximum;
        }

        private void forEachBinderNodeAlternative(
                TypedENode source,
                NodeAlternativeConsumer consumer) throws IOException {
            requireBoundedOrbitSyntax(source);
            forEachNodePortAlternative(
                    source, source, 0, new ArrayList<>(), consumer);
        }

        private static void requireBoundedOrbitSyntax(TypedENode source)
                throws IOException {
            int maximum = maximumSerializedOrbitDepth();
            if (source.ports().size() > maximum) {
                throw uncheckable(
                        "serialized orbit root arity exceeds configured recursion bound "
                                + maximum);
            }
            ArrayDeque<PortDepth> pending = new ArrayDeque<>();
            for (PortValue port : source.ports()) {
                pending.addLast(new PortDepth(port, 1));
            }
            while (!pending.isEmpty()) {
                PortDepth current = pending.removeFirst();
                if (current.depth() > maximum) {
                    throw uncheckable(
                            "serialized orbit port depth exceeds configured recursion bound "
                                    + maximum);
                }
                PortValue port = current.port();
                if (port instanceof BindPort) {
                    pending.addLast(new PortDepth(
                            ((BindPort) port).body(), current.depth() + 1));
                } else if (port instanceof BindBlockPort) {
                    pending.addLast(new PortDepth(
                            ((BindBlockPort) port).body(), current.depth() + 1));
                } else {
                    List<? extends PortValue> children;
                    if (port instanceof SeqPort) {
                        children = ((SeqPort) port).elements();
                    } else if (port instanceof BagPort) {
                        children = ((BagPort) port).occurrences();
                    } else if (port instanceof SetPort) {
                        children = ((SetPort) port).elements();
                    } else {
                        continue;
                    }
                    if (children.size() > maximum) {
                        throw uncheckable(
                                "serialized orbit container arity exceeds configured recursion bound "
                                        + maximum);
                    }
                    for (PortValue child : children) {
                        pending.addLast(new PortDepth(child, current.depth() + 1));
                    }
                }
            }
        }

        private static int maximumSerializedOrbitDepth() {
            int maximum = Integer.getInteger(
                    "acgn.maxCanonicalRecursionDepth",
                    DEFAULT_MAX_SERIALIZED_ORBIT_DEPTH);
            if (maximum <= 0) {
                throw new IllegalStateException(
                        "acgn.maxCanonicalRecursionDepth must be positive");
            }
            return maximum;
        }

        private void forEachNodePortAlternative(
                TypedENode enclosingRoot,
                TypedENode source,
                int index,
                List<PortValue> prefix,
                NodeAlternativeConsumer consumer) throws IOException {
            if (index == source.ports().size()) {
                consumer.accept(source.rebuildCanonicalCandidate(
                        source.context(), List.copyOf(prefix)));
                return;
            }
            forEachBinderPortAlternative(
                    enclosingRoot,
                    source.ports().get(index),
                    new ArrayList<>(List.of(index)),
                    alternative -> {
                        prefix.add(alternative);
                        forEachNodePortAlternative(
                                enclosingRoot,
                                source,
                                index + 1,
                                prefix,
                                consumer);
                        prefix.remove(prefix.size() - 1);
                    });
        }

        private void forEachBinderPortAlternative(
                TypedENode enclosingRoot,
                PortValue source,
                List<Integer> path,
                PortAlternativeConsumer consumer) throws IOException {
            if (source instanceof OnePort) {
                consumer.accept(source);
                return;
            }
            if (source instanceof BindPort) {
                BindPort bind = (BindPort) source;
                forEachBinderPortAlternative(
                        enclosingRoot,
                        bind.body(),
                        childPath(path, 0),
                        body -> consumer.accept(new BindPort(
                                bind.schema(),
                                bind.context(),
                                bind.boundSlot(),
                                body)));
                return;
            }
            if (source instanceof BindBlockPort) {
                BindBlockPort block = (BindBlockPort) source;
                BinderBlockDescriptor descriptor = block.schema().descriptor();
                TypedPermutation identity = TypedPermutation.identity(
                        descriptor.boundContext());
                descriptor.automorphisms().forEachElementChecked(automorphism -> {
                    BindBlockPort acted = automorphism.equals(identity)
                            ? block
                            : BinderOccurrenceAutomorphismCertificate.create(
                                    enclosingRoot, block, path, automorphism).target();
                    forEachBinderPortAlternative(
                            enclosingRoot,
                            acted.body(),
                            childPath(path, 0),
                            body -> consumer.accept(new BindBlockPort(
                                    acted.schema(),
                                    acted.context(),
                                    acted.descriptorToOccurrence(),
                                    body)));
                });
                return;
            }

            List<? extends PortValue> children;
            if (source instanceof SeqPort) {
                children = ((SeqPort) source).elements();
            } else if (source instanceof BagPort) {
                children = ((BagPort) source).occurrences();
            } else if (source instanceof SetPort) {
                children = ((SetPort) source).elements();
            } else {
                throw uncheckable(
                        "unknown binder-orbit port " + source.getClass().getName());
            }
            forEachContainerAlternative(
                    enclosingRoot,
                    source,
                    children,
                    path,
                    0,
                    new ArrayList<>(),
                    consumer);
        }

        private void forEachContainerAlternative(
                TypedENode enclosingRoot,
                PortValue source,
                List<? extends PortValue> children,
                List<Integer> path,
                int index,
                List<PortValue> prefix,
                PortAlternativeConsumer consumer) throws IOException {
            if (index == children.size()) {
                List<PortValue> elements = List.copyOf(prefix);
                if (source instanceof SeqPort) {
                    consumer.accept(new SeqPort(
                            ((SeqPort) source).schema(), source.context(), elements));
                } else if (source instanceof BagPort) {
                    consumer.accept(new BagPort(
                            ((BagPort) source).schema(), source.context(), elements));
                } else {
                    consumer.accept(new SetPort(
                            ((SetPort) source).schema(), source.context(), elements));
                }
                return;
            }
            forEachBinderPortAlternative(
                    enclosingRoot,
                    children.get(index),
                    childPath(path, index),
                    alternative -> {
                        prefix.add(alternative);
                        forEachContainerAlternative(
                                enclosingRoot,
                                source,
                                children,
                                path,
                                index + 1,
                                prefix,
                                consumer);
                        prefix.remove(prefix.size() - 1);
                    });
        }

        private static List<Integer> childPath(List<Integer> parent, int index) {
            List<Integer> result = new ArrayList<>(parent);
            result.add(index);
            return result;
        }

        private Node parentEdgeProof(ParentEdgeCertificate edge)
                throws IOException {
            String key = edge.structuralKey().stableString();
            Node prior = edgeProofs.get(key);
            if (prior != null) {
                return prior;
            }
            TypedEqualityCertificate supplied = edge.endpointDerivation();
            boolean reversed = supplied.category()
                    == CertificateCategory.EQUATIONAL_SYMMETRY;
            TypedEqualityCertificate ground = reversed
                    ? supplied.premises().get(0) : supplied;
            if (!(ground instanceof InputEquationCertificate)) {
                throw uncheckable("parent edge lacks a serializable ground origin");
            }
            InputEquationCertificate equation = (InputEquationCertificate) ground;
            Node child = term(TypedInvocation.identity(edge.child()));
            Node parent = term(edge.parentInvocation());
            Node axiomLeft = reversed ? parent : child;
            Node axiomRight = reversed ? child : parent;
            String axiomId = axiomId(equation.origin());
            registerAxiom(axiomId, pattern(axiomLeft), pattern(axiomRight));
            Node axiom = proof(
                    "AXIOM",
                    context(edge.child().exposedSlots()),
                    "TERM",
                    type(edge.child().outputType()),
                    axiomLeft,
                    axiomRight,
                    List.of(),
                    node(
                            "axiom-instance",
                            List.of(
                                    axiomId,
                                    scalar(context(edge.child().exposedSlots()), 0)),
                            List.of(
                                    node("type-substitution", List.of()),
                                    node("term-substitution", List.of()),
                                    node("side-evidence", List.of()))));
            Node oriented = axiom;
            if (reversed) {
                oriented = proof(
                        "SYM",
                        context(edge.child().exposedSlots()),
                        "TERM",
                        type(edge.child().outputType()),
                        child,
                        parent,
                        List.of(axiom),
                        node("sym", List.of()));
            }
            Node edgeProof = proof(
                    "PARENT_EDGE",
                    context(edge.child().exposedSlots()),
                    "TERM",
                    type(edge.child().outputType()),
                    child,
                    parent,
                    List.of(oriented),
                    leaf(
                            "parent-edge",
                            witnessId(edge.child()),
                            witnessId(edge.parent()),
                            scalar(embedding(edge.embedding()), 0)));
            edgeProofs.put(key, edgeProof);
            return edgeProof;
        }

        private void registerAxiom(String id, Node left, Node right)
                throws IOException {
            Node axiom = node(
                    "axiom",
                    List.of(id),
                    List.of(
                            left,
                            right,
                            node("type-variables", List.of(), List.of()),
                            node("term-variables", List.of()),
                            node("side-conditions", List.of())));
            internManifest(axioms, id, axiom, "axiom");
        }

        private Node pattern(Node term) throws IOException {
            String kind = term.scalars.get(1);
            List<String> scalars = new ArrayList<>();
            scalars.add(kind);
            scalars.add(term.scalars.get(3));
            scalars.add(term.scalars.get(4));
            scalars.add(term.scalars.get(5));
            scalars.addAll(term.scalars.subList(6, term.scalars.size()));
            List<Node> children = new ArrayList<>();
            for (Node child : term.children) {
                Node childTerm = tables.terms.get(scalar(child, 0));
                if (childTerm == null) {
                    throw uncheckable("axiom pattern references an absent term");
                }
                children.add(pattern(childTerm));
            }
            return node("pattern", scalars, children);
        }

        private Node snapshot(CertificateTraceSnapshot snapshot)
                throws IOException {
            Node existing = snapshots.get(snapshot.stateKey());
            if (existing != null) {
                return existing;
            }
            List<Node> classes = new ArrayList<>();
            for (TypedEClassRecord record : snapshot.classes().values()) {
                classes.add(leaf(
                        "class",
                        record.id().toString(),
                        witnessId(record.interfaceView()),
                        scalar(context(record.exposedSlots()), 0),
                        type(record.outputType())));
            }
            List<Node> parents = new ArrayList<>();
            for (ParentAssignment assignment : snapshot.parents().values()) {
                if (assignment.isRoot()) {
                    continue;
                }
                ParentStep step = assignment.provenancePath().steps().get(0);
                Node proof = parentEdgeProof(step.certificate());
                Node map = embedding(assignment.parentInvocation().embedding());
                parents.add(leaf(
                        "parent",
                        edgeId(step.certificate()),
                        assignment.child().id().toString(),
                        assignment.parentInvocation().eclass().id().toString(),
                        scalar(map, 0),
                        scalar(proof, 0)));
            }
            List<Node> shapes = new ArrayList<>();
            for (ParentRecordKey key : snapshot.shapeCertificates().keySet()) {
                InsertionWire wire = shapeWire(snapshot, key);
                Node shapeTerm = wire.kernel;
                TypedEClassRecord owner = snapshot.classes().get(key.owner());
                ShapeWitness witness = owner == null
                        ? null : owner.shapeWitnesses().get(key.shape());
                if (witness == null) {
                    throw uncheckable(
                            "snapshot shape lacks its exact occurrence witness");
                }
                CanonicalizationResult structural = wire.insertion
                        .canonicalization().structural();
                TypedSlotContext serializedContext = isIdentity(structural.witness())
                        ? structural.kernel().context()
                        : structural.shape().node().context();
                if (!serializedContext.equals(owner.exposedSlots())) {
                    throw uncheckable(
                            "fresh shape and owner definition do not share the exact "
                                    + "schema v10 context");
                }
                Node occurrence = embedding(TypedEmbedding.identity(serializedContext));
                Node ownerAmbient = embedding(TypedEmbedding.identity(
                        owner.exposedSlots()));
                Node ownerProof = key.owner().equals(
                        wire.insertion.insertedClass().id())
                        ? proof(
                                "REFL",
                                context(serializedContext),
                                "TERM",
                                type(owner.outputType()),
                                shapeTerm,
                                shapeTerm,
                                List.of(),
                                leaf("refl", scalar(shapeTerm, 0)))
                        : rehomedShapeOwnerProof(
                                snapshot, key, wire, owner, shapeTerm);
                shapes.add(leaf(
                        "shape",
                        shapeId(key.owner(), shapeTerm),
                        key.owner().toString(),
                        scalar(shapeTerm, 0),
                        scalar(wire.replay, 0),
                        scalar(occurrence, 0),
                        scalar(ownerAmbient, 0),
                        scalar(ownerProof, 0)));
            }
            List<Node> hashes = new ArrayList<>();
            for (Map.Entry<CanonicalShape, Set<EClassId>> entry
                    : snapshot.hashCons().entrySet()) {
                for (EClassId owner : entry.getValue()) {
                    InsertionWire wire = shapeWire(
                            snapshot, new ParentRecordKey(owner, entry.getKey()));
                    hashes.add(leaf(
                            "hash-owner",
                            termKey(wire.kernel),
                            owner.toString()));
                }
            }
            List<Node> parentUses = new ArrayList<>();
            for (Map.Entry<EClassId, Set<ParentRecordKey>> entry
                    : snapshot.parentUses().entrySet()) {
                for (ParentRecordKey use : entry.getValue()) {
                    InsertionWire wire = shapeWire(snapshot, use);
                    parentUses.add(leaf(
                            "parent-use", entry.getKey().toString(), shapeId(use)));
                }
            }
            List<Node> dirty = new ArrayList<>();
            for (ParentRecordKey key : snapshot.dirtyParents()) {
                InsertionWire wire = shapeWire(snapshot, key);
                dirty.add(leaf("dirty-shape", shapeId(key)));
            }
            Node encoded = tables.snapshot(
                    snapshot.revision(),
                    snapshot.status().name(),
                    classes,
                    parents,
                    shapes,
                    hashes,
                    parentUses,
                    List.of(),
                    dirty);
            snapshots.put(snapshot.stateKey(), encoded);
            return encoded;
        }

        private Node rehomedShapeOwnerProof(
                CertificateTraceSnapshot snapshot,
                ParentRecordKey key,
                InsertionWire childWire,
                TypedEClassRecord owner,
                Node shapeTerm) throws IOException {
            ParentEdgeCertificate edge = slice.union();
            if (edge == null
                    || !edge.child().id().equals(
                            childWire.insertion.insertedClass().id())
                    || !edge.parent().id().equals(key.owner())
                    || !isIdentity(edge.embedding())) {
                throw uncheckable(
                        "rehomed shape lacks the exact supported direct parent edge");
            }
            InsertionWire parentWire = insertionWires.get(key.owner());
            if (parentWire == null
                    || !shapeTerm.equals(childWire.kernel)
                    || !childWire.insertion.canonicalization().structural()
                            .kernel().context().equals(owner.exposedSlots())
                    || !parentWire.insertion.canonicalization().structural()
                            .kernel().context().equals(owner.exposedSlots())) {
                throw uncheckable(
                        "rehomed shape definitions do not share the exact owner context");
            }
            ParentAssignment assignment = snapshot.parents().get(edge.child().id());
            if (assignment == null
                    || assignment.isRoot()
                    || assignment.provenancePath().steps().size() != 1
                    || !assignment.provenancePath().steps().get(0)
                            .certificate().equals(edge)) {
                throw uncheckable(
                        "rehomed shape snapshot lacks its exact primitive parent path");
            }

            Node childUnfold = witnessUnfold(
                    childWire,
                    TypedInvocation.identity(edge.child()));
            Node shapeToChild = proof(
                    "SYM",
                    context(owner.exposedSlots()),
                    "TERM",
                    type(owner.outputType()),
                    childWire.kernel,
                    term(TypedInvocation.identity(edge.child())),
                    List.of(childUnfold),
                    node("sym", List.of()));
            Node edgeProof = parentEdgeProof(edge);
            Node shapeToParentInvocation = proof(
                    "TRANS",
                    context(owner.exposedSlots()),
                    "TERM",
                    type(owner.outputType()),
                    childWire.kernel,
                    term(edge.parentInvocation()),
                    List.of(shapeToChild, edgeProof),
                    node("trans", List.of()));
            Node parentUnfold = witnessUnfold(parentWire, edge.parentInvocation());
            return proof(
                    "TRANS",
                    context(owner.exposedSlots()),
                    "TERM",
                    type(owner.outputType()),
                    childWire.kernel,
                    parentWire.kernel,
                    List.of(shapeToParentInvocation, parentUnfold),
                    node("trans", List.of()));
        }

        private Node witnessUnfold(
                InsertionWire wire,
                TypedInvocation invocation) throws IOException {
            if (!wire.insertion.insertedClass().equals(invocation.eclass())
                    || !isIdentity(invocation.embedding())
                    || !wire.insertion.canonicalization().structural()
                            .kernel().context().equals(invocation.callerContext())) {
                throw uncheckable(
                        "supported witness unfolding requires one identity invocation");
            }
            Node invoked = term(invocation);
            Node identity = embedding(invocation.embedding());
            return proof(
                    "WITNESS_UNFOLD",
                    context(invocation.callerContext()),
                    "TERM",
                    type(invocation.outputType()),
                    invoked,
                    wire.kernel,
                    List.of(),
                    leaf(
                            "witness-unfold",
                            wire.witnessId,
                            scalar(identity, 0)));
        }

        private void buildEvents() throws IOException {
            for (CertificateTraceEvent event : slice.events()) {
                Node payload;
                switch (event.kind()) {
                    case INSERT_FRESH -> {
                        InsertionWire wire = insertionWires.get(
                                insertion(event).insertedClass().id());
                        payload = leaf(
                                "insert-fresh",
                                wire.eclassId,
                                wire.shapeId,
                                scalar(wire.replay, 0),
                                scalar(wire.orbit, 0),
                                scalar(wire.fresh, 0));
                    }
                    case UNION -> payload = leaf(
                            "union",
                            scalar(parentEdgeProof(
                                    ((CertificateTracePayload.Union) event.payload())
                                            .certificate()), 0));
                    case REBUILD_START -> payload = leaf(
                            "rebuild-start", scalar(snapshot(event.before()), 0));
                    case REBUILD_COMPLETE -> payload = leaf(
                            "rebuild-complete",
                            "false");
                    default -> throw new AssertionError(event.kind());
                }
                tables.event(node(
                        "event",
                        List.of(
                                Long.toString(event.sequence()),
                                event.kind().name(),
                                scalar(snapshot(event.before()), 0),
                                scalar(snapshot(event.after()), 0)),
                        List.of(payload)));
            }
        }

        private Node buildUnfolding(Node finalSnapshot) throws IOException {
            FiniteUnfoldingTree tree = slice.unfolding();
            List<FiniteUnfoldingStepIndex> steps = tree.indexTrace().steps();
            StepCursor cursor = new StepCursor(steps);
            RepWire rep = rep(tree, cursor);
            if (!cursor.exhausted()) {
                throw uncheckable("unfolding index trace contains extra steps");
            }
            return tables.unfolding(withContentId(
                    "unfolding",
                    List.of(
                            scalar(rep.invocation, 0),
                            Integer.toString(tree.height()),
                            scalar(rep.normalized, 0),
                            scalar(finalSnapshot, 0)),
                    List.of(rep.rep)));
        }

        private RepWire rep(FiniteUnfoldingTree tree, StepCursor cursor)
                throws IOException {
            FiniteUnfoldingStepIndex step = cursor.next();
            if (!step.selectedShape().equals(tree.selectedShape())
                    || !step.shapeWitness().equals(tree.shapeWitness())
                    || !step.invocationAtStep().equals(tree.rootInvocation())
                    || !step.freshCoordinates().isEmpty()) {
                throw uncheckable("unfolding index step differs from the retained Rep node");
            }
            ParentRecordKey selected = new ParentRecordKey(
                    tree.rootInvocation().eclass().id(), tree.selectedShape());
            InsertionWire shape = shapeWire(session.finalSnapshot(), selected);
            CanonicalizationResult shapeResult = shape.insertion
                    .canonicalization().structural();
            boolean normalizeFreeOrbit = !isIdentity(shapeResult.witness());
            TypedEmbedding restoredExtension = normalizeFreeOrbit
                    ? TypedEmbedding.identity(shapeResult.shape().node().context())
                    : step.restoredExtension();
            TypedENode restored = normalizeFreeOrbit
                    ? shapeResult.shape().node()
                    : tree.restoredRoot().act(restoredExtension);
            Node restoredTerm = term(restored);
            List<InvocationOccurrence> sourceOccurrences =
                    sourceInvocationOccurrences(restored);
            if (sourceOccurrences.size() != tree.invocationChildren().size()) {
                throw uncheckable("Rep children do not cover restored invocations");
            }
            List<RepWire> sourceChildren = new ArrayList<>();
            for (int index = 0; index < tree.invocationChildren().size(); index++) {
                FiniteUnfoldingTree childTree = tree.invocationChildren().get(index);
                if (!sourceOccurrences.get(index).invocation().equals(
                        childTree.rootInvocation())) {
                    throw uncheckable(
                            "Rep child order differs from source occurrence order");
                }
                sourceChildren.add(rep(childTree, cursor));
            }
            List<InvocationOccurrence> wireOccurrences =
                    wireInvocationOccurrences(restored);
            List<RepWire> wireChildren = reorderOccurrenceValues(
                    sourceOccurrences, sourceChildren, wireOccurrences);
            List<Node> childRecords = new ArrayList<>();
            for (int index = 0; index < wireChildren.size(); index++) {
                RepWire child = wireChildren.get(index);
                childRecords.add(node(
                        "rep-child",
                        List.of(encodePath(wireOccurrences.get(index).path())),
                        List.of(child.rep)));
            }
            Node normalized = expandedTerm(restored, sourceChildren);
            Node invocation = term(step.invocationAtStep());
            Node rep = node(
                    "rep",
                    List.of(
                            scalar(invocation, 0),
                            shapeId(selected),
                            scalar(restoredTerm, 0),
                            Integer.toString(tree.height())),
                    List.of(
                            leaf(
                                    "ambient-extension",
                                    scalar(embedding(restoredExtension), 0)),
                            node("redundant-assignments", List.of()),
                            node("rep-children", childRecords)));
            return new RepWire(rep, invocation, normalized);
        }

        private Node expandedTerm(TypedENode node, List<RepWire> children)
                throws IOException {
            int[] cursor = {0};
            List<Node> ports = new ArrayList<>();
            for (PortValue port : node.ports()) {
                ports.add(expandedPort(port, children, cursor));
            }
            if (cursor[0] != children.size()) {
                throw uncheckable("expanded term has extra unfolding children");
            }
            return tables.term(
                    "APP",
                    context(node.context()),
                    "TERM",
                    type(node.outputType()),
                    operatorId(node.operator()),
                    List.of(),
                    ports);
        }

        private Node expandedPort(
                PortValue port,
                List<RepWire> children,
                int[] cursor) throws IOException {
            if (port instanceof OnePort) {
                OnePort one = (OnePort) port;
                if (one.leaf() instanceof SlotPortLeaf) {
                    return term(one);
                }
                if (cursor[0] >= children.size()) {
                    throw uncheckable("expanded term lacks an unfolding child");
                }
                Node child = children.get(cursor[0]++).normalized;
                String schema = schemaId(one.schema());
                return tables.term(
                        "ONE_TERM",
                        context(one.context()),
                        "PORT",
                        schema,
                        schema,
                        List.of(),
                        List.of(child));
            }
            List<? extends PortValue> sourceChildren;
            String kind;
            List<String> attributes = List.of();
            if (port instanceof SeqPort) {
                kind = "SEQ";
                sourceChildren = ((SeqPort) port).elements();
            } else if (port instanceof BagPort) {
                kind = "BAG";
                sourceChildren = ((BagPort) port).occurrences();
            } else if (port instanceof SetPort) {
                kind = "SET";
                sourceChildren = ((SetPort) port).elements();
            } else if (port instanceof BindPort) {
                kind = "BIND";
                BindPort bind = (BindPort) port;
                sourceChildren = List.of(bind.body());
                attributes = List.of(slotName(bind.boundSlot()));
            } else if (port instanceof BindBlockPort) {
                kind = "BIND_BLOCK";
                BindBlockPort block = (BindBlockPort) port;
                sourceChildren = List.of(block.body());
                attributes = List.of(scalar(
                        embedding(block.descriptorToOccurrence()), 0));
            } else {
                throw uncheckable(
                        "unknown expanded port " + port.getClass().getName());
            }
            List<Node> expandedChildren = new ArrayList<>(sourceChildren.size());
            for (PortValue child : sourceChildren) {
                expandedChildren.add(expandedPort(child, children, cursor));
            }
            normalizeWireContainerChildren(kind, expandedChildren);
            String schema = schemaId(port.schema());
            return tables.term(
                    kind,
                    context(port.context()),
                    "PORT",
                    schema,
                    schema,
                    attributes,
                    expandedChildren);
        }

        private Node publication(
                Node finalSnapshot,
                Node unfolding,
                String theoryDigest) throws IOException {
            List<Node> ec = new ArrayList<>();
            for (TypedEClassRecord record : session.finalSnapshot().classes().values()) {
                ec.add(leaf("ec", record.id().toString(), witnessId(record.interfaceView())));
            }
            List<Node> pc = new ArrayList<>();
            for (ParentAssignment assignment
                    : session.finalSnapshot().parents().values()) {
                if (assignment.isRoot()) {
                    continue;
                }
                ParentEdgeCertificate edge = assignment.provenancePath()
                        .steps().get(0).certificate();
                pc.add(leaf(
                        "pc",
                        edgeId(edge),
                        scalar(parentEdgeProof(edge), 0)));
            }
            InsertionWire root = insertionWires.get(
                    slice.rootInsertion().insertedClass().id());
            String normalized = unfolding.scalars.get(3);
            return node(
                    "publication",
                    List.of(
                            scalar(finalSnapshot, 0),
                            Long.toString(session.finalSnapshot().revision()),
                            scalar(root.source, 0),
                            normalized,
                            theoryDigest),
                    List.of(
                            tables.sortedSection("ec-evidence", ec),
                            tables.sortedSection("pc-evidence", pc),
                            node("sc-evidence", List.of()),
                            node(
                                    "canonical-refs",
                                    tables.canonicalRecords.values().stream()
                                            .map(value -> leaf(
                                                    "canonical-ref", scalar(value, 0)))
                                            .toList()),
                            node(
                                    "unfolding-refs",
                                    List.of(leaf(
                                            "unfolding-ref", scalar(unfolding, 0))))));
        }

        private Node theory() {
            return node(
                    "theory",
                    CertificateTheoryManifest.scalars(),
                    List.of(node("axioms", new ArrayList<>(axioms.values()))));
        }

        private Node vocabulary() {
            Node evidence = semanticEvidence();
            return node(
                    "vocabulary",
                    List.of(CertificateTheoryManifest.VOCABULARY_POLICY),
                    List.of(
                            node("schemas", new ArrayList<>(schemas.values())),
                            node("operators", new ArrayList<>(operators.values())),
                            node("binders", new ArrayList<>(binders.values())),
                            evidence));
        }

        private Node semanticEvidence() {
            SemanticProfile profile = session.artifact().semanticProfile();
            collectExactTypes();
            List<Node> laws = new ArrayList<>();
            for (Map.Entry<String, List<ContainerLawDeclaration>> entry
                    : session.artifact().containerLaws().entrySet()) {
                for (ContainerLawDeclaration declaration : entry.getValue()) {
                    for (ContainerLawCertificate certificate
                            : declaration.certificates().values()) {
                        laws.add(lawCertificate(certificate));
                    }
                }
            }
            laws.sort(Comparator.comparing(value -> scalar(value, 0)));
            List<Node> types = new ArrayList<>();
            types.addAll(exactTypes.values());
            List<Node> flatConstructions = new ArrayList<>();
            session.artifact().flatConstructions().stream()
                    .map(this::flatConstruction)
                    .forEach(flatConstructions::add);
            session.artifact().dependentChainConstructions().stream()
                    .map(this::dependentChainConstruction)
                    .forEach(flatConstructions::add);
            flatConstructions.sort(Comparator.comparing(value -> scalar(value, 0)));
            List<Node> containerConstructions = session.artifact()
                    .containerConstructions().stream()
                    .map(this::containerConstruction)
                    .sorted(Comparator.comparing(value -> scalar(value, 0)))
                    .toList();
            List<Node> binderOccurrences = session.artifact()
                    .binderOccurrenceCertificates().stream()
                    .map(certificate -> binderOccurrence(
                            certificate, binderRoot(certificate)))
                    .sorted(Comparator.comparing(value -> scalar(value, 0)))
                    .toList();
            List<Node> callOccurrences = session.artifact()
                    .callOccurrenceCertificates().stream()
                    .map(this::callOccurrence)
                    .sorted(Comparator.comparing(value -> scalar(value, 0)))
                    .toList();
            return node(
                    "semantic-evidence",
                    List.of(
                            Integer.toString(profile.bitwidth()),
                            profile.overflowMode().name(),
                            profile.temporalMode(),
                            profile.rewriteMode(),
                            profile.signatureVersion(),
                            profile.fingerprint(),
                            AlloyLawRegistry.VERSION,
                            AlloyLawRegistry.SOURCE_THEORY_DIGEST),
                    List.of(
                            node("law-certificates", laws),
                            node("flat-constructions", flatConstructions),
                            node("container-constructions", containerConstructions),
                            node("binder-occurrences", binderOccurrences),
                            node("exact-types", types),
                            node("call-occurrences", callOccurrences)));
        }

        private Node callOccurrence(CallOccurrenceCertificate certificate) {
            try {
                Node source = term(certificate.sourceEndpoint());
                List<Node> arguments = new ArrayList<>(certificate.declaredArity());
                List<StructuralKey> argumentKeys = new ArrayList<>(
                        certificate.declaredArity());
                for (int role = 0; role < certificate.orderedArguments().size(); role++) {
                    String endpoint = scalar(
                            term(certificate.orderedArguments().get(role)), 0);
                    arguments.add(leaf(
                            "call-argument",
                            Integer.toString(role),
                            endpoint));
                    argumentKeys.add(StructuralKey.leaf(
                            "alloy-call-wire-argument-v1",
                            Integer.toString(role),
                            endpoint));
                }
                StructuralKey wireKey = StructuralKey.of(
                        "alloy-call-wire-occurrence-v1",
                        List.of(
                                Long.toString(certificate.occurrenceId()),
                                certificate.sourcePath(),
                                certificate.sourceName(),
                                certificate.qualifiedCallee(),
                                certificate.kind(),
                                Integer.toString(certificate.declaredArity()),
                                certificate.arityAuthority().name()),
                        List.of(
                                StructuralKey.leaf(
                                        "alloy-call-wire-source-term-v1",
                                        scalar(source, 0)),
                                StructuralKey.branch(
                                        "alloy-call-wire-ordered-arguments-v1",
                                        argumentKeys)));
                callOccurrenceAnchor(certificate, wireKey);
                return node(
                        "call-occurrence",
                        List.of(
                                wireKey.stableString(),
                                Long.toString(certificate.occurrenceId()),
                                certificate.sourcePath(),
                                certificate.sourceName(),
                                certificate.qualifiedCallee(),
                                certificate.kind(),
                                Integer.toString(certificate.declaredArity()),
                                certificate.arityAuthority().name(),
                                scalar(source, 0)),
                        arguments);
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "A validated CALL occurrence cannot fail serialization",
                        exception);
            }
        }

        private void callOccurrenceAnchor(
                CallOccurrenceCertificate certificate,
                StructuralKey wireKey)
                throws IOException {
            String markerIdentity = CALL_OCCURRENCE_ANCHOR_PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(
                            encodeCanonicalUtf8(wireKey.stableString()));
            String markerOperatorId = "operator/" + contentId(node(
                    "operator-id", List.of(markerIdentity), List.of()));
            String outputType = type(certificate.sourceEndpoint().outputType());
            internManifest(
                    operators,
                    markerOperatorId,
                    node(
                            "operator",
                            List.of(
                                    markerOperatorId,
                                    outputType,
                                    markerIdentity,
                                    "none"),
                            List.of()),
                    "operator");
            tables.term(
                    "APP",
                    context(certificate.sourceEndpoint().context()),
                    "TERM",
                    outputType,
                    markerOperatorId,
                    List.of(),
                    List.of());
        }

        private Node flatConstruction(FlatConstructionCertificate certificate) {
            try {
                Node target = certificate.collapsedToSingleton()
                        ? term(certificate.singletonTarget())
                        : term(certificate.target());
                List<Node> splices = new ArrayList<>();
                for (FlatConstructionCertificate.Splice splice
                        : certificate.splices()) {
                    splices.add(leaf(
                            "splice",
                            encodePath(splice.path()),
                            Integer.toString(splice.outerArity()),
                            Integer.toString(splice.nestedArity()),
                            Integer.toString(splice.position()),
                            splice.nestedSource().stableString()));
                }
                return node(
                        "flat-construction",
                        List.of(
                                certificate.structuralKey().stableString(),
                                certificate.semanticProfile().fingerprint(),
                                operatorId(certificate.source().operator()),
                                certificate.path().toString(),
                                certificate.collapsedToSingleton()
                                        ? "SINGLETON" : "NODE",
                                scalar(target, 0),
                                certificate.leftEndpoint().structuralKey().stableString(),
                                certificate.rightEndpoint().structuralKey().stableString(),
                                constructionSourceOwner(certificate)),
                        List.of(
                                flatInput(certificate.source()),
                                node("splices", splices),
                                containerTrace(certificate.containerTrace())));
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "A validated flat construction cannot fail serialization",
                        exception);
            }
        }

        private Node flatInput(FlatInput input) throws IOException {
            if (input instanceof FlatLeaf) {
                return leaf("flat-leaf", scalar(term(((FlatLeaf) input).port()), 0));
            }
            FlatApplication application = (FlatApplication) input;
            List<Node> children = new ArrayList<>();
            for (FlatInput operand : application.operands()) {
                children.add(flatInput(operand));
            }
            return node(
                    "flat-application",
                    List.of(
                            operatorId(application.operator()),
                            scalar(context(application.context()), 0),
                            Integer.toString(application.operands().size()),
                            application.structuralKey().stableString()),
                    children);
        }

        private Node dependentChainConstruction(
                DependentChainCertificate certificate) {
            try {
                Node target = term(certificate.target());
                return node(
                        "dependent-chain-construction",
                        List.of(
                                certificate.structuralKey().stableString(),
                                certificate.semanticProfile().fingerprint(),
                                certificate.source().kind().name(),
                                scalar(target, 0),
                                certificate.leftEndpoint().structuralKey().stableString(),
                                certificate.rightEndpoint().structuralKey().stableString(),
                                constructionSourceOwner(certificate),
                                DependentChainTheory.VERSION,
                                DependentChainTheory.DIGEST,
                                certificate.theoryIndex().stableString(),
                                certificate.sourceOccurrenceCommitment().stableString()),
                        List.of(dependentChainInput(certificate.source())));
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "A validated dependent chain cannot fail serialization",
                        exception);
            }
        }

        private Node dependentChainInput(DependentChainInput input)
                throws IOException {
            if (input instanceof DependentChainLeaf) {
                DependentChainLeaf leaf = (DependentChainLeaf) input;
                return node(
                        "dependent-chain-leaf",
                        List.of(
                                scalar(term(leaf.port()), 0),
                                type(leaf.outputType()),
                                leaf.typeRule().name(),
                                leaf.structuralKey().stableString(),
                                leaf.typeProof().stableString()),
                        List.of(dependentTypeDag(leaf.outputTypeDag())));
            }
            DependentChainApplication application =
                    (DependentChainApplication) input;
            return node(
                    "dependent-chain-application",
                    List.of(
                            application.kind().name(),
                            scalar(context(application.context()), 0),
                            type(application.outputType()),
                            application.structuralKey().stableString()),
                    List.of(
                            dependentChainInput(application.left()),
                            dependentChainInput(application.right()),
                            dependentTypeDag(application.outputTypeDag()),
                            dependentCombinationCases(
                                    application.combinationCases())));
        }

        private Node dependentTypeDag(DependentTypeDag dag) {
            List<Node> products = new ArrayList<>();
            for (int index = 0; index < dag.alternatives().size(); index++) {
                products.add(dependentTypeProduct(
                        index, dag.alternatives().get(index)));
            }
            return node(
                    "dependent-type-dag",
                    List.of(
                            type(dag.relationType()),
                            dag.commonAncestorType().map(this::type)
                                    .orElse("NONE"),
                            Integer.toString(dag.arity()),
                            dag.structuralKey().stableString()),
                    products);
        }

        private Node dependentTypeProduct(
                int index,
                List<DependentColumnEvidence> columns) {
            List<Node> encoded = new ArrayList<>();
            for (DependentColumnEvidence column : columns) {
                List<Node> ancestry = column.ancestry().stream()
                        .map(type -> leaf("dependent-chain-ancestor", type(type)))
                        .toList();
                encoded.add(node(
                        "dependent-chain-column",
                        List.of(
                                type(column.exactColumn()),
                                column.structuralKey().stableString()),
                        ancestry));
            }
            return node(
                    "dependent-type-product",
                    List.of(
                            Integer.toString(index),
                            type(DependentChainKind.typeOf(columns)),
                            StructuralKey.branch(
                                    "dependent-type-product-v1",
                                    columns.stream()
                                            .map(DependentColumnEvidence::structuralKey)
                                            .toList())
                                    .stableString()),
                    encoded);
        }

        private Node dependentCombinationCases(
                List<DependentTypeDag.CombinationCase> cases) {
            return node(
                    "dependent-chain-combination-cases",
                    List.of(Integer.toString(cases.size())),
                    cases.stream().map(this::dependentCombinationCase).toList());
        }

        private Node dependentCombinationCase(
                DependentTypeDag.CombinationCase proof) {
            Node boundary = proof.boundary()
                    .map(this::dependentBoundary)
                    .orElseGet(() -> leaf(
                            "dependent-chain-no-boundary", "ARROW"));
            Node result = proof.resultAlternative()
                    .map(columns -> dependentTypeProduct(-1, columns))
                    .orElseGet(() -> leaf(
                            "dependent-chain-no-result", "DISJOINT"));
            return node(
                    "dependent-chain-combination-case",
                    List.of(
                            Integer.toString(proof.leftAlternative()),
                            Integer.toString(proof.rightAlternative()),
                            proof.decision().name(),
                            proof.structuralKey().stableString()),
                    List.of(boundary, result));
        }

        private Node dependentBoundary(
                DependentBoundaryCorrespondence boundary) {
            return node(
                    "dependent-chain-boundary",
                    List.of(
                            boundary.rule().name(),
                            type(boundary.leftBoundary()),
                            type(boundary.rightBoundary()),
                            boundary.overlaps()
                                    ? type(boundary.meetBoundary()) : "NONE",
                            type(boundary.commonAncestor()),
                            boundary.structuralKey().stableString()),
                    List.of(
                            node(
                                    "dependent-chain-boundary-left-path",
                                    List.of(),
                                    boundary.leftWitnessPath().stream()
                                            .map(type -> leaf(
                                                    "dependent-chain-boundary-step",
                                                    type(type)))
                                            .toList()),
                            node(
                                    "dependent-chain-boundary-right-path",
                                    List.of(),
                                    boundary.rightWitnessPath().stream()
                                            .map(type -> leaf(
                                                    "dependent-chain-boundary-step",
                                                    type(type)))
                                            .toList())));
        }

        private Node containerConstruction(
                ContainerConstructionCertificate certificate) {
            try {
                Node target = term(certificate.target());
                List<Node> inputs = new ArrayList<>();
                for (PortValue input : certificate.inputOccurrences()) {
                    inputs.add(leaf("input", scalar(term(input), 0)));
                }
                return node(
                        "container-construction",
                        List.of(
                                certificate.structuralKey().stableString(),
                                certificate.semanticProfile().fingerprint(),
                                operatorId(certificate.operator()),
                                certificate.path().toString(),
                                scalar(target, 0),
                                certificate.leftEndpoint().structuralKey().stableString(),
                                certificate.rightEndpoint().structuralKey().stableString(),
                                constructionSourceOwner(certificate)),
                        List.of(
                                node("input-occurrences", inputs),
                                containerTrace(certificate.containerTrace())));
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "A validated container construction cannot fail serialization",
                        exception);
            }
        }

        private Node containerTrace(ContainerApplicationTrace trace)
                throws IOException {
            List<Node> children = new ArrayList<>();
            for (PortValue input : trace.inputOccurrences()) {
                children.add(leaf("trace-input", scalar(term(input), 0)));
            }
            for (int index = 0; index < trace.outputOccurrences().size(); index++) {
                List<String> scalars = new ArrayList<>();
                scalars.add(scalar(term(trace.outputOccurrences().get(index)), 0));
                for (Integer source : trace.outputFibers().get(index)) {
                    scalars.add(Integer.toString(source));
                }
                children.add(node("trace-output", scalars, List.of()));
            }
            return node(
                    "container-trace",
                    List.of(
                            schemaId(trace.schema()),
                            scalar(context(trace.context()), 0),
                            Integer.toString(trace.inputOccurrences().size()),
                            Integer.toString(trace.outputOccurrences().size()),
                            trace.structuralKey().stableString()),
                    children);
        }

        private TypedENode binderRoot(
                BinderOccurrenceAutomorphismCertificate certificate) {
            TypedENode root = certificate.enclosingRoot();
            for (TypedEClassRecord record
                    : session.finalSnapshot().classes().values()) {
                for (CanonicalShape shape : record.shapeWitnesses().keySet()) {
                    if (shape.node().equals(root)
                            && BinderOccurrenceProofs.collect(root).contains(certificate)) {
                        return root;
                    }
                }
            }
            throw new IllegalStateException(
                    "Binder occurrence has no retained source root");
        }

        private Node binderOccurrence(
                BinderOccurrenceAutomorphismCertificate certificate,
                TypedENode root) {
            try {
                TypedRenaming bodyAction = TypedRenaming.identity(
                        certificate.source().context())
                        .disjointUnion(certificate.occurrencePermutation())
                        .asRenaming();
                retainBinderActionEmbeddings(certificate.source().body(), bodyAction);
                return leaf(
                        "binder-occurrence",
                        certificate.structuralKey().stableString(),
                        scalar(term(certificate.source()), 0),
                        scalar(term(certificate.target()), 0),
                        encodePath(certificate.sourcePath()),
                        scalar(embedding(certificate.automorphism()), 0),
                        scalar(embedding(certificate.occurrencePermutation()), 0),
                        certificate.leftEndpoint().structuralKey().stableString(),
                        certificate.rightEndpoint().structuralKey().stableString(),
                        scalar(term(root), 0));
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "A validated binder occurrence cannot fail serialization",
                        exception);
            }
        }

        private void retainBinderActionEmbeddings(
                PortValue source,
                TypedEmbedding action) throws IOException {
            if (!source.context().equals(action.source())) {
                throw new IllegalStateException(
                        "Binder action serialization starts at the wrong port context");
            }
            embedding(action);
            if (source instanceof BindPort) {
                BindPort bind = (BindPort) source;
                TypedSlot target = CanonicalSlotAlphabet.fresh(
                        bind.schema().boundType(),
                        SlotAlphabet.CANONICAL_BOUND,
                        action.codomain());
                retainBinderActionEmbeddings(
                        bind.body(), action.disjointExtension(bind.boundSlot(), target));
                return;
            }
            if (source instanceof BindBlockPort) {
                BindBlockPort block = (BindBlockPort) source;
                TypedRenaming targetOccurrence = block.schema().descriptor()
                        .freshOccurrenceRenaming(action.codomain());
                TypedRenaming oldToTarget = block.descriptorToOccurrence()
                        .inverse()
                        .andThen(targetOccurrence);
                retainBinderActionEmbeddings(
                        block.body(), action.disjointUnion(oldToTarget));
                return;
            }
            List<? extends PortValue> children;
            if (source instanceof SeqPort) {
                children = ((SeqPort) source).elements();
            } else if (source instanceof BagPort) {
                children = ((BagPort) source).occurrences();
            } else if (source instanceof SetPort) {
                children = ((SetPort) source).elements();
            } else {
                return;
            }
            for (PortValue child : children) {
                retainBinderActionEmbeddings(child, action);
            }
        }

        private Node lawCertificate(ContainerLawCertificate certificate) {
            CertificateOrigin origin = certificate.origin();
            return leaf(
                    "law-certificate",
                    certificate.lawIndex().stableString(),
                    certificate.authority().name(),
                    certificate.operatorIdentity(),
                    type(certificate.resultType()),
                    scalar(exactType(certificate.resultType()), 0),
                    certificate.schemaPath().toString(),
                    certificate.law().name(),
                    certificate.sourceTheoryDigest(),
                    uncheckedSchemaId(certificate.schema()),
                    certificate.schema().structuralKey().stableString(),
                    certificate.lawParameter().stableString(),
                    certificate.leftSourceEndpoint().stableString(),
                    certificate.rightSourceEndpoint().stableString(),
                    origin.kind().name(),
                    origin.sourceArtifact(),
                    origin.declarationId(),
                    Integer.toString(origin.ordinal()));
        }

        private void collectExactTypes() {
            for (TypedEClassRecord record : session.finalSnapshot().classes().values()) {
                collectExactType(record.outputType());
                collectContextTypes(record.exposedSlots());
                for (CanonicalShape shape : record.shapeWitnesses().keySet()) {
                    collectNodeTypes(shape.node());
                }
            }
            for (List<ContainerLawDeclaration> declarations
                    : session.artifact().containerLaws().values()) {
                for (ContainerLawDeclaration declaration : declarations) {
                    for (ContainerLawCertificate certificate
                            : declaration.certificates().values()) {
                        collectExactType(certificate.resultType());
                        collectSchemaTypes(certificate.schema());
                    }
                }
            }
            for (FlatConstructionCertificate construction
                    : session.artifact().flatConstructions()) {
                collectFlatInputTypes(construction.source());
                if (construction.collapsedToSingleton()) {
                    collectPortTypes(construction.singletonTarget());
                } else {
                    collectNodeTypes(construction.target());
                }
            }
            for (ContainerConstructionCertificate construction
                    : session.artifact().containerConstructions()) {
                collectNodeTypes(construction.target());
                construction.inputOccurrences().forEach(this::collectPortTypes);
            }
            for (DependentChainCertificate construction
                    : session.artifact().dependentChainConstructions()) {
                collectDependentChainTypes(construction.source());
                collectDependentFoldTypes(construction.source());
                collectNodeTypes(construction.target());
            }
            for (BinderOccurrenceAutomorphismCertificate occurrence
                    : session.artifact().binderOccurrenceCertificates()) {
                collectPortTypes(occurrence.source());
                collectPortTypes(occurrence.target());
            }
            for (CallOccurrenceCertificate occurrence
                    : session.artifact().callOccurrenceCertificates()) {
                collectNodeTypes(occurrence.sourceEndpoint());
                occurrence.orderedArguments().forEach(this::collectPortTypes);
            }
        }

        private void collectFlatInputTypes(FlatInput input) {
            if (input instanceof FlatLeaf) {
                collectPortTypes(((FlatLeaf) input).port());
                return;
            }
            FlatApplication application = (FlatApplication) input;
            collectExactType(application.outputType());
            collectContextTypes(application.context());
            for (PortSchema schema : application.operator().portSchemas()) {
                collectSchemaTypes(schema);
            }
            application.operands().forEach(this::collectFlatInputTypes);
        }

        private void collectDependentChainTypes(DependentChainInput input) {
            collectDependentDagTypes(input.outputTypeDag());
            collectContextTypes(input.context());
            if (input instanceof DependentChainLeaf) {
                collectPortTypes(((DependentChainLeaf) input).port());
                return;
            }
            DependentChainApplication application =
                    (DependentChainApplication) input;
            collectDependentCaseTypes(application.combinationCases());
            collectDependentChainTypes(application.left());
            collectDependentChainTypes(application.right());
        }

        private void collectDependentFoldTypes(DependentChainApplication source) {
            // Replay checks the canonical left fold as well as the source association.
            List<DependentChainLeaf> leaves = source.leafInputs();
            DependentTypeDag folded = leaves.get(0).outputTypeDag();
            collectDependentDagTypes(folded);
            for (int index = 1; index < leaves.size(); index++) {
                DependentTypeDag.ChainCombination step = DependentTypeDag.combine(
                        source.kind(), folded, leaves.get(index).outputTypeDag());
                collectDependentCaseTypes(step.cases());
                folded = step.result();
                collectDependentDagTypes(folded);
            }
            if (!folded.equals(source.outputTypeDag())) {
                throw new IllegalStateException(
                        "A dependent-chain type ledger must retain the certified fold result");
            }
        }

        private void collectDependentDagTypes(DependentTypeDag dag) {
            collectExactType(dag.relationType());
            dag.alternatives().forEach(this::collectDependentProductTypes);
            dag.commonAncestorType().ifPresent(this::collectExactType);
        }

        private void collectDependentProductTypes(List<DependentColumnEvidence> product) {
            collectExactType(DependentChainKind.typeOf(product));
            for (DependentColumnEvidence column : product) {
                collectExactType(column.exactColumn());
                column.ancestry().forEach(this::collectExactType);
            }
        }

        private void collectDependentCaseTypes(List<DependentTypeDag.CombinationCase> cases) {
            for (DependentTypeDag.CombinationCase proof : cases) {
                proof.boundary().ifPresent(boundary -> {
                    collectExactType(boundary.leftBoundary());
                    collectExactType(boundary.rightBoundary());
                    if (boundary.overlaps()) {
                        collectExactType(boundary.meetBoundary());
                    }
                    collectExactType(boundary.commonAncestor());
                    boundary.leftWitnessPath().forEach(this::collectExactType);
                    boundary.rightWitnessPath().forEach(this::collectExactType);
                });
                proof.resultAlternative().ifPresent(this::collectDependentProductTypes);
            }
        }

        private void collectNodeTypes(TypedENode node) {
            collectExactType(node.outputType());
            collectContextTypes(node.context());
            for (PortSchema schema : node.operator().portSchemas()) {
                collectSchemaTypes(schema);
            }
            for (PortValue port : node.ports()) {
                collectPortTypes(port);
            }
        }

        private void collectPortTypes(PortValue port) {
            collectContextTypes(port.context());
            collectSchemaTypes(port.schema());
            if (port instanceof OnePort) {
                PortLeaf leaf = ((OnePort) port).leaf();
                if (leaf instanceof SlotPortLeaf) {
                    collectExactType(((SlotPortLeaf) leaf).slot().type());
                } else {
                    TypedInvocation invocation =
                            ((InvocationPortLeaf) leaf).invocation();
                    collectExactType(invocation.outputType());
                    collectContextTypes(invocation.callerContext());
                    collectContextTypes(invocation.eclass().exposedSlots());
                }
                return;
            }
            if (port instanceof SeqPort) {
                ((SeqPort) port).elements().forEach(this::collectPortTypes);
            } else if (port instanceof BagPort) {
                ((BagPort) port).occurrences().forEach(this::collectPortTypes);
            } else if (port instanceof SetPort) {
                ((SetPort) port).elements().forEach(this::collectPortTypes);
            } else if (port instanceof BindPort) {
                collectExactType(((BindPort) port).boundSlot().type());
                collectPortTypes(((BindPort) port).body());
            } else if (port instanceof BindBlockPort) {
                BindBlockPort block = (BindBlockPort) port;
                collectContextTypes(block.boundContext());
                collectPortTypes(block.body());
            }
        }

        private void collectSchemaTypes(PortSchema schema) {
            if (schema instanceof OnePortSchema) {
                collectExactType(((OnePortSchema) schema).type());
            } else if (schema instanceof SeqPortSchema) {
                SeqPortSchema sequence = (SeqPortSchema) schema;
                if (sequence.isDependent()) {
                    sequence.positionalElementSchemas()
                            .forEach(this::collectSchemaTypes);
                } else {
                    collectSchemaTypes(sequence.elementSchema());
                }
            } else if (schema instanceof BagPortSchema) {
                collectSchemaTypes(((BagPortSchema) schema).elementSchema());
            } else if (schema instanceof SetPortSchema) {
                collectSchemaTypes(((SetPortSchema) schema).elementSchema());
            } else if (schema instanceof BindPortSchema) {
                BindPortSchema bind = (BindPortSchema) schema;
                collectExactType(bind.boundType());
                collectSchemaTypes(bind.bodySchema());
            } else if (schema instanceof BindBlockPortSchema) {
                BindBlockPortSchema block = (BindBlockPortSchema) schema;
                collectContextTypes(block.descriptor().boundContext());
                collectSchemaTypes(block.bodySchema());
            }
        }

        private void collectContextTypes(TypedSlotContext context) {
            for (TypedSlot slot : context) {
                collectExactType(slot.type());
            }
        }

        private void collectExactType(GraphType graphType) {
            exactType(graphType);
            for (GraphType argument : graphType.arguments()) {
                collectExactType(argument);
            }
        }

        private String uncheckedSchemaId(PortSchema schema) {
            try {
                return schemaId(schema);
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "A validated law schema cannot fail serialization", exception);
            }
        }

        private Node context(TypedSlotContext context) throws IOException {
            return tables.context(context);
        }

        private Node embedding(TypedEmbedding embedding) throws IOException {
            return tables.embedding(embedding);
        }

        private Node term(TypedENode node) throws IOException {
            String operator = operatorId(node.operator());
            List<Node> children = new ArrayList<>();
            for (PortValue port : node.ports()) {
                children.add(term(port));
            }
            return tables.term(
                    "APP",
                    context(node.context()),
                    "TERM",
                    type(node.outputType()),
                    operator,
                    List.of(),
                    children);
        }

        private Node term(PortValue port) throws IOException {
            String schema = schemaId(port.schema());
            if (port instanceof OnePort) {
                OnePort one = (OnePort) port;
                if (one.leaf() instanceof SlotPortLeaf) {
                    return tables.term(
                            "ONE_SLOT",
                            context(one.context()),
                            "PORT",
                            schema,
                            schema,
                            List.of(slotName(((SlotPortLeaf) one.leaf()).slot())),
                            List.of());
                }
                TypedInvocation invocation =
                        ((InvocationPortLeaf) one.leaf()).invocation();
                return tables.term(
                        "ONE_TERM",
                        context(one.context()),
                        "PORT",
                        schema,
                        schema,
                        List.of(),
                        List.of(term(invocation)));
            }
            if (port instanceof SeqPort) {
                return containerTerm(
                        "SEQ", port, schema, ((SeqPort) port).elements());
            }
            if (port instanceof BagPort) {
                return containerTerm(
                        "BAG", port, schema, ((BagPort) port).occurrences());
            }
            if (port instanceof SetPort) {
                return containerTerm(
                        "SET", port, schema, ((SetPort) port).elements());
            }
            if (port instanceof BindPort) {
                BindPort bind = (BindPort) port;
                embedding(TypedEmbedding.identity(bind.body().context()));
                return tables.term(
                        "BIND",
                        context(bind.context()),
                        "PORT",
                        schema,
                        schema,
                        List.of(slotName(bind.boundSlot())),
                        List.of(term(bind.body())));
            }
            if (port instanceof BindBlockPort) {
                BindBlockPort block = (BindBlockPort) port;
                embedding(TypedEmbedding.identity(block.body().context()));
                return tables.term(
                        "BIND_BLOCK",
                        context(block.context()),
                        "PORT",
                        schema,
                        schema,
                        List.of(scalar(embedding(block.descriptorToOccurrence()), 0)),
                        List.of(term(block.body())));
            }
            throw uncheckable("unknown port value " + port.getClass().getName());
        }

        private Node containerTerm(
                String kind,
                PortValue container,
                String schema,
                List<? extends PortValue> elements) throws IOException {
            List<Node> children = new ArrayList<>(elements.size());
            for (PortValue element : elements) {
                children.add(term(element));
            }
            normalizeWireContainerChildren(kind, children);
            return tables.term(
                    kind,
                    context(container.context()),
                    "PORT",
                    schema,
                    schema,
                    List.of(),
                    children);
        }

        private static void normalizeWireContainerChildren(
                String kind, List<Node> children) throws IOException {
            if (!kind.equals("BAG") && !kind.equals("SET")) {
                return;
            }
            children.sort(Comparator.comparing(value -> scalar(value, 0)));
            if (kind.equals("SET")) {
                for (int index = 1; index < children.size(); index++) {
                    if (scalar(children.get(index - 1), 0).equals(
                            scalar(children.get(index), 0))) {
                        throw uncheckable(
                                "producer and wire SET quotients disagree on duplicate operands");
                    }
                }
            }
        }

        private List<InvocationOccurrence> wireInvocationOccurrences(TypedENode node)
                throws IOException {
            List<InvocationOccurrence> result = new ArrayList<>();
            for (int index = 0; index < node.ports().size(); index++) {
                collectWireInvocationOccurrences(
                        node.ports().get(index),
                        new ArrayList<>(List.of(index)),
                        result);
            }
            return List.copyOf(result);
        }

        private void collectWireInvocationOccurrences(
                PortValue port,
                List<Integer> path,
                List<InvocationOccurrence> result) throws IOException {
            if (port instanceof OnePort) {
                PortLeaf leaf = ((OnePort) port).leaf();
                if (leaf instanceof InvocationPortLeaf) {
                    List<Integer> occurrencePath = new ArrayList<>(path);
                    occurrencePath.add(0);
                    result.add(new InvocationOccurrence(
                            List.copyOf(occurrencePath),
                            ((InvocationPortLeaf) leaf).invocation()));
                }
                return;
            }
            List<? extends PortValue> children = wireOrderedChildren(port);
            for (int index = 0; index < children.size(); index++) {
                List<Integer> childPath = new ArrayList<>(path);
                childPath.add(index);
                collectWireInvocationOccurrences(children.get(index), childPath, result);
            }
        }

        private List<? extends PortValue> wireOrderedChildren(PortValue port)
                throws IOException {
            List<? extends PortValue> children;
            boolean unordered = false;
            boolean set = false;
            if (port instanceof SeqPort) {
                children = ((SeqPort) port).elements();
            } else if (port instanceof BagPort) {
                children = ((BagPort) port).occurrences();
                unordered = true;
            } else if (port instanceof SetPort) {
                children = ((SetPort) port).elements();
                unordered = true;
                set = true;
            } else if (port instanceof BindPort) {
                return List.of(((BindPort) port).body());
            } else if (port instanceof BindBlockPort) {
                return List.of(((BindBlockPort) port).body());
            } else {
                throw uncheckable(
                        "unknown wire port value " + port.getClass().getName());
            }
            if (!unordered) {
                return children;
            }
            List<WirePortChild> ordered = new ArrayList<>(children.size());
            for (PortValue child : children) {
                ordered.add(new WirePortChild(scalar(term(child), 0), child));
            }
            ordered.sort(Comparator.comparing(WirePortChild::termId));
            if (set) {
                for (int index = 1; index < ordered.size(); index++) {
                    if (ordered.get(index - 1).termId().equals(
                            ordered.get(index).termId())) {
                        throw uncheckable(
                                "producer and wire SET quotients disagree on duplicate operands");
                    }
                }
            }
            return ordered.stream().map(WirePortChild::value).toList();
        }

        private static <T> List<T> reorderOccurrenceValues(
                List<InvocationOccurrence> sourceOccurrences,
                List<? extends T> sourceValues,
                List<InvocationOccurrence> targetOccurrences) throws IOException {
            if (sourceOccurrences.size() != sourceValues.size()
                    || sourceOccurrences.size() != targetOccurrences.size()) {
                throw uncheckable("invocation occurrence reordering changes cardinality");
            }
            boolean[] used = new boolean[sourceOccurrences.size()];
            List<T> result = new ArrayList<>(targetOccurrences.size());
            for (InvocationOccurrence target : targetOccurrences) {
                int match = -1;
                for (int index = 0; index < sourceOccurrences.size(); index++) {
                    if (!used[index]
                            && sourceOccurrences.get(index).invocation().equals(
                                    target.invocation())) {
                        match = index;
                        break;
                    }
                }
                if (match < 0) {
                    throw uncheckable(
                            "wire invocation occurrence has no source provenance");
                }
                used[match] = true;
                result.add(sourceValues.get(match));
            }
            return List.copyOf(result);
        }

        private Node term(TypedInvocation invocation) throws IOException {
            return tables.term(
                    "INVOKE",
                    context(invocation.callerContext()),
                    "TERM",
                    type(invocation.outputType()),
                    witnessId(invocation.eclass()),
                    List.of(scalar(embedding(invocation.embedding()), 0)),
                    List.of());
        }

        private static List<String> witnessOrder(TypedRenaming witness) {
            List<TypedSlot> sources = new ArrayList<>(witness.source().slots());
            sources.sort(Comparator.comparing(Assembler::slotName));
            return sources.stream()
                    .map(source -> slotName(witness.apply(source)))
                    .toList();
        }

        private Node proof(
                String variant,
                Node context,
                String sortKind,
                String sortValue,
                Node left,
                Node right,
                List<Node> premises,
                Node payload) throws IOException {
            return tables.proof(
                    variant, context, sortKind, sortValue,
                    left, right, premises, payload);
        }

        private String schemaId(PortSchema schema) throws IOException {
            String id = "schema/" + contentId(node(
                    "schema-id", List.of(schema.structuralKey().stableString()), List.of()));
            String value;
            String arity;
            String quotient;
            List<Node> children = new ArrayList<>(1);
            if (schema instanceof OnePortSchema) {
                value = type(((OnePortSchema) schema).type());
                arity = "FINITE:1";
                quotient = "RIGID";
            } else if (schema instanceof SeqPortSchema) {
                SeqPortSchema sequence = (SeqPortSchema) schema;
                value = "";
                arity = arity(sequence.arityPolicy());
                quotient = sequence.siblingQuotient().name();
                if (sequence.isDependent()) {
                    for (PortSchema positional
                            : sequence.positionalElementSchemas()) {
                        children.add(leaf("schema-ref", schemaId(positional)));
                    }
                } else {
                    children.add(leaf(
                            "schema-ref", schemaId(sequence.elementSchema())));
                }
            } else if (schema instanceof BagPortSchema) {
                BagPortSchema bag = (BagPortSchema) schema;
                value = "";
                arity = arity(bag.arityPolicy());
                quotient = bag.siblingQuotient().name();
                children.add(leaf("schema-ref", schemaId(bag.elementSchema())));
            } else if (schema instanceof SetPortSchema) {
                SetPortSchema set = (SetPortSchema) schema;
                value = "";
                arity = arity(set.arityPolicy());
                quotient = set.siblingQuotient().name();
                children.add(leaf("schema-ref", schemaId(set.elementSchema())));
            } else if (schema instanceof BindPortSchema) {
                BindPortSchema bind = (BindPortSchema) schema;
                value = type(bind.boundType());
                arity = "FINITE:1";
                quotient = "RIGID";
                children.add(leaf("schema-ref", schemaId(bind.bodySchema())));
            } else if (schema instanceof BindBlockPortSchema) {
                BindBlockPortSchema block = (BindBlockPortSchema) schema;
                value = binderId(block.descriptor());
                arity = "FINITE:1";
                quotient = "RIGID";
                children.add(leaf("schema-ref", schemaId(block.bodySchema())));
            } else {
                throw uncheckable("unknown port schema " + schema.getClass().getName());
            }
            internManifest(
                    schemas,
                    id,
                    node(
                            "schema",
                            List.of(
                                    id,
                                    schema instanceof SeqPortSchema
                                                    && ((SeqPortSchema) schema).isDependent()
                                            ? "DEPENDENT_SEQ" : schema.kind().name(),
                                    value,
                                    arity,
                                    quotient),
                            children),
                    "schema");
            return id;
        }

        private String binderId(BinderBlockDescriptor descriptor) throws IOException {
            String id = "binder/" + contentId(node(
                    "binder-id",
                    List.of(descriptor.structuralKey().stableString()),
                    List.of()));
            List<Node> children = new ArrayList<>();
            List<BinderCoordinateDescriptor> coordinates = descriptor.coordinates();
            for (int index = 0; index < coordinates.size(); index++) {
                BinderCoordinateDescriptor coordinate = coordinates.get(index);
                List<String> dependencies = coordinate.dependencies().slots().stream()
                        .map(Assembler::slotName)
                        .toList();
                children.add(node(
                        "coordinate",
                        List.of(
                                Integer.toString(index),
                                slotName(coordinate.canonicalSlot()),
                                type(coordinate.type()),
                                coordinate.quantifier(),
                                Integer.toString(coordinate.disjointnessClass()),
                                coordinate.domain().stableString(),
                                coordinate.multiplicity(),
                                Integer.toString(coordinate.exchangeClass())),
                        List.of(node("dependencies", dependencies, List.of()))));
            }
            for (TypedPermutation generator : descriptor.automorphisms().generators()) {
                List<String> image = new ArrayList<>(coordinates.size());
                for (BinderCoordinateDescriptor coordinate : coordinates) {
                    TypedSlot target = generator.apply(coordinate.canonicalSlot());
                    int targetIndex = -1;
                    for (int index = 0; index < coordinates.size(); index++) {
                        if (coordinates.get(index).canonicalSlot().equals(target)) {
                            targetIndex = index;
                            break;
                        }
                    }
                    if (targetIndex < 0) {
                        throw uncheckable("binder generator leaves its descriptor");
                    }
                    image.add(Integer.toString(targetIndex));
                }
                children.add(node("generator", image, List.of()));
            }
            internManifest(binders, id, node("binder", List.of(id), children), "binder");
            return id;
        }

        private static String arity(ArityPolicy policy) {
            if (policy.kind() == ArityPolicy.Kind.AT_LEAST) {
                return "AT_LEAST:" + policy.minimum();
            }
            return "FINITE:" + String.join(",", policy.finiteArities().stream()
                    .map(Object::toString).toList());
        }

        private String operatorId(InstantiatedOperator operator)
                throws IOException {
            String structuralKey = operator.structuralKey().stableString();
            String id;
            if (operator.declaration().typeParameters().isEmpty()) {
                id = "operator/" + contentId(node(
                        "operator-id", List.of(structuralKey), List.of()));
            } else {
                byte[] structuralKeyBytes = encodeCanonicalUtf8(structuralKey);
                String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        structuralKeyBytes);
                id = POLYMORPHIC_OPERATOR_KEY_PREFIX
                        + HexFormat.of().formatHex(sha256(
                                structuralKeyBytes))
                        + "/" + encoded;
            }
            List<Node> ports = new ArrayList<>();
            for (PortSchema schema : operator.portSchemas()) {
                ports.add(leaf("schema-ref", schemaId(schema)));
            }
            internManifest(
                    operators,
                    id,
                    node(
                            "operator",
                            List.of(
                                    id,
                                    type(operator.outputType()),
                                    operator.operator(),
                                    operator.flatLicense().enabled()
                                            ? operator.flatLicense().path().toString()
                                            : "none"),
                            ports),
                    "operator");
            return id;
        }

        private String witnessId(TypedEClassInterface eclass) throws IOException {
            String id = witnessIds.get(eclass.id());
            InsertionEvidence evidence = slice.insertions().get(eclass.id());
            if (id == null || evidence == null
                    || !evidence.insertion().insertedClass().equals(eclass)) {
                throw uncheckable("invocation references an unretained or stale e-class");
            }
            return id;
        }

        private String axiomId(CertificateOrigin origin) {
            return "axiom/" + contentId(node(
                    "origin-derived-axiom-id",
                    List.of(
                            origin.kind().name(),
                            origin.sourceArtifact(),
                            origin.declarationId(),
                            Integer.toString(origin.ordinal())),
                    List.of()));
        }

        private String shapeId(ParentRecordKey key) throws IOException {
            InsertionWire wire = shapeWires.get(key);
            Node shapeTerm = wire == null ? term(key.shape().node()) : wire.kernel;
            return shapeId(key.owner(), shapeTerm);
        }

        private String shapeId(EClassId owner, Node shapeTerm) {
            return "shape/" + contentId(node(
                    "producer-shape-id",
                    List.of(
                            owner.toString(),
                            scalar(shapeTerm, 0)),
                    List.of()));
        }

        private InsertionWire shapeWire(
                CertificateTraceSnapshot snapshot,
                ParentRecordKey key) throws IOException {
            InsertionWire exact = shapeWires.get(key);
            if (exact != null) {
                return exact;
            }
            InsertionWire selected = null;
            for (InsertionWire candidate : insertionWires.values()) {
                EClassId inserted = candidate.insertion.insertedClass().id();
                if (!candidate.insertion.canonicalization().shape().equals(key.shape())
                        || !snapshot.classes().containsKey(inserted)
                        || !snapshotLeader(snapshot, inserted).equals(key.owner())) {
                    continue;
                }
                if (selected == null
                        || candidate.insertion.insertedClass().id().compareTo(
                                selected.insertion.insertedClass().id()) < 0) {
                    selected = candidate;
                }
            }
            if (selected == null) {
                throw uncheckable(
                        "owner-qualified shape has no retained source insertion");
            }
            shapeWires.put(key, selected);
            return selected;
        }

        private EClassId snapshotLeader(
                CertificateTraceSnapshot snapshot,
                EClassId source) throws IOException {
            EClassId current = source;
            Set<EClassId> seen = new HashSet<>();
            while (seen.add(current)) {
                ParentAssignment assignment = snapshot.parents().get(current);
                if (assignment == null) {
                    throw uncheckable("snapshot omits an insertion parent assignment");
                }
                if (assignment.isRoot()) {
                    return current;
                }
                current = assignment.parentInvocation().eclass().id();
            }
            throw uncheckable("snapshot parent assignments contain a cycle");
        }

        private String edgeId(ParentEdgeCertificate edge) {
            return "edge/" + contentId(node(
                    "producer-parent-edge-id",
                    List.of(edge.structuralKey().stableString()),
                    List.of()));
        }

        private String termKey(Node term) throws IOException {
            return contentId(tables.structuralTerm(term));
        }

        private static String slotName(TypedSlot slot) {
            return slot.toString();
        }

        private String slotOrder(TypedSlot slot) {
            return type(slot.type()) + "\u0000" + slotName(slot);
        }

        private String type(GraphType graphType) {
            Node exact = exactType(graphType);
            return session.provenance().testOnly()
                    ? graphType.toString()
                    : scalar(exact, 0);
        }

        private Node exactType(GraphType graphType) {
            if (graphType.symbol() != null
                    && !AlloyTypeBridge.isAdmittedIdentity(graphType.symbol())) {
                throw new IllegalArgumentException(
                        "Exact type symbols must be well-formed visible identities");
            }
            List<Node> arguments = new ArrayList<>();
            for (GraphType argument : graphType.arguments()) {
                arguments.add(leaf("type-ref", scalar(exactType(argument), 0)));
            }
            Node encoded = withContentId(
                    "exact-type",
                    List.of(
                            graphType.kind().name(),
                            graphType.symbol() == null ? "" : graphType.symbol()),
                    arguments);
            Node prior = exactTypes.putIfAbsent(scalar(encoded, 0), encoded);
            if (prior != null && !prior.equals(encoded)) {
                throw new IllegalStateException(
                        "Exact type content-ID collision at " + scalar(encoded, 0));
            }
            return encoded;
        }

        private static String encodePath(List<Integer> path) {
            return String.join("/", path.stream().map(Object::toString).toList());
        }

        private static void internManifest(
                Map<String, Node> target,
                String id,
                Node value,
                String kind) throws IOException {
            Node prior = target.putIfAbsent(id, value);
            if (prior != null && !prior.equals(value)) {
                throw uncheckable(kind + " ID collision at " + id);
            }
        }
    }

    private static List<InvocationOccurrence> sourceInvocationOccurrences(TypedENode node) {
        List<InvocationOccurrence> result = new ArrayList<>();
        for (int index = 0; index < node.ports().size(); index++) {
            collectInvocationOccurrences(
                    node.ports().get(index), new ArrayList<>(List.of(index)), result);
        }
        return List.copyOf(result);
    }

    private static void collectInvocationOccurrences(
            PortValue port,
            List<Integer> path,
            List<InvocationOccurrence> result) {
        if (port instanceof OnePort) {
            PortLeaf leaf = ((OnePort) port).leaf();
            if (leaf instanceof InvocationPortLeaf) {
                List<Integer> occurrencePath = new ArrayList<>(path);
                occurrencePath.add(0);
                result.add(new InvocationOccurrence(
                        List.copyOf(occurrencePath),
                        ((InvocationPortLeaf) leaf).invocation()));
            }
            return;
        }
        List<? extends PortValue> children;
        if (port instanceof SeqPort) {
            children = ((SeqPort) port).elements();
        } else if (port instanceof BagPort) {
            children = ((BagPort) port).occurrences();
        } else if (port instanceof SetPort) {
            children = ((SetPort) port).elements();
        } else if (port instanceof BindPort) {
            children = List.of(((BindPort) port).body());
        } else if (port instanceof BindBlockPort) {
            children = List.of(((BindBlockPort) port).body());
        } else {
            throw new IllegalStateException(
                    "Unhandled port value " + port.getClass().getName());
        }
        for (int index = 0; index < children.size(); index++) {
            List<Integer> childPath = new ArrayList<>(path);
            childPath.add(index);
            collectInvocationOccurrences(children.get(index), childPath, result);
        }
    }

    private static byte[] encode(Node root) {
        byte[] payload = encodeNode(root);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                    MAGIC.length + 2 + 8 + payload.length + 32);
            DataOutputStream output = new DataOutputStream(bytes);
            output.write(MAGIC);
            output.writeShort(FORMAT_VERSION);
            output.writeLong(payload.length);
            output.write(payload);
            output.write(sha256(payload));
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError("Byte-array encoding cannot fail", exception);
        }
    }

    private static byte[] encodeNode(Node root) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeNode(output, root);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError("Byte-array encoding cannot fail", exception);
        }
    }

    private static void writeNode(DataOutputStream output, Node node)
            throws IOException {
        writeString(output, node.tag);
        output.writeInt(node.scalars.size());
        for (String scalar : node.scalars) {
            writeString(output, scalar);
        }
        output.writeInt(node.children.size());
        for (Node child : node.children) {
            writeNode(output, child);
        }
    }

    private static void writeString(DataOutputStream output, String value)
            throws IOException {
        byte[] encoded = encodeCanonicalUtf8(value);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    static byte[] encodeCanonicalUtf8(String value) {
        Objects.requireNonNull(value, "canonical string");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "Canonical certificate strings must be well-formed Unicode",
                    exception);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("JDK 17 must provide SHA-256", exception);
        }
    }

    private static String contentId(Node node) {
        return HexFormat.of().formatHex(sha256(encodeNode(node)));
    }

    private static Node withContentId(
            String tag,
            List<String> contentScalars,
            List<Node> children) {
        List<String> scalars = new ArrayList<>(contentScalars.size() + 1);
        scalars.add("");
        scalars.addAll(contentScalars);
        Node provisional = node(tag, scalars, children);
        scalars.set(0, contentId(node(
                tag + "/content",
                provisional.scalars.subList(1, provisional.scalars.size()),
                provisional.children)));
        return node(tag, scalars, children);
    }

    private static Node leaf(String tag, String... scalars) {
        return node(tag, Arrays.asList(scalars), List.of());
    }

    private static Node node(String tag, List<Node> children) {
        return node(tag, List.of(), children);
    }

    private static Node node(String tag, List<String> scalars, List<Node> children) {
        return new Node(tag, scalars, children);
    }

    private static String scalar(Node node, int index) {
        return node.scalars.get(index);
    }

    @FunctionalInterface
    private interface TypeEncoder {
        String encode(GraphType type);
    }

    @FunctionalInterface
    private interface NodeAlternativeConsumer {
        void accept(TypedENode node) throws IOException;
    }

    @FunctionalInterface
    private interface PortAlternativeConsumer {
        void accept(PortValue port) throws IOException;
    }

    private static final class Tables {
        private final TypeEncoder typeEncoder;
        private final Map<String, Node> contexts = new TreeMap<>();
        private final Map<String, Node> embeddings = new TreeMap<>();
        private final Map<String, Node> terms = new TreeMap<>();
        private final Map<String, Node> proofs = new TreeMap<>();
        private final Map<String, Node> witnesses = new TreeMap<>();
        private final Map<String, Node> snapshots = new TreeMap<>();
        private final List<Node> events = new ArrayList<>();
        private final Map<String, Node> canonicalRecords = new TreeMap<>();
        private final Map<String, Node> unfoldings = new TreeMap<>();

        private Tables(TypeEncoder typeEncoder) {
            this.typeEncoder = Objects.requireNonNull(typeEncoder, "typeEncoder");
        }

        private Node context(TypedSlotContext context) throws IOException {
            List<TypedSlot> ordered = new ArrayList<>(context.slots());
            ordered.sort(Comparator.comparing(
                    slot -> typeEncoder.encode(slot.type()) + "\u0000"
                            + Assembler.slotName(slot)));
            List<Node> slots = ordered.stream()
                    .map(slot -> leaf(
                            "slot", Assembler.slotName(slot),
                            typeEncoder.encode(slot.type())))
                    .toList();
            return internContent(contexts, withContentId(
                    "context", List.of(), slots), "context");
        }

        private Node embedding(TypedEmbedding embedding) throws IOException {
            if (!embedding.mapping().keySet().equals(embedding.source().slots())
                    || !embedding.isInjective()
                    || !embedding.isTypePreserving()) {
                throw uncheckable("embedding is missing, duplicate, or ill typed");
            }
            Node source = context(embedding.source());
            Node target = context(embedding.codomain());
            String kind = embedding.isRenaming() ? "BIJECTION" : "INJECTION";
            List<TypedSlot> ordered = new ArrayList<>(embedding.source().slots());
            ordered.sort(Comparator.comparing(
                    slot -> typeEncoder.encode(slot.type()) + "\u0000"
                            + Assembler.slotName(slot)));
            List<Node> images = new ArrayList<>();
            Set<TypedSlot> targets = new HashSet<>();
            for (TypedSlot slot : ordered) {
                TypedSlot image = embedding.mapping().get(slot);
                if (image == null || !targets.add(image)
                        || !embedding.codomain().contains(image)
                        || !slot.type().equals(image.type())) {
                    throw uncheckable("embedding image is absent, duplicate, or ill typed");
                }
                images.add(leaf(
                        "image", Assembler.slotName(slot), Assembler.slotName(image)));
            }
            if ("BIJECTION".equals(kind)
                    && targets.size() != embedding.codomain().size()) {
                throw uncheckable("embedding falsely claims to be a bijection");
            }
            return internContent(
                    embeddings,
                    withContentId(
                            "embedding",
                            List.of(kind, scalar(source, 0), scalar(target, 0)),
                            images),
                    "embedding");
        }

        private Node term(
                String kind,
                Node context,
                String sortKind,
                String sortValue,
                String symbol,
                List<String> attributes,
                List<Node> children) throws IOException {
            List<String> scalars = new ArrayList<>(List.of(
                    kind, scalar(context, 0), sortKind, sortValue, symbol));
            scalars.addAll(attributes);
            Node record = withContentId(
                    "term",
                    scalars,
                    children.stream()
                            .map(child -> leaf("term-ref", scalar(child, 0)))
                            .toList());
            return internContent(terms, record, "term");
        }

        private Node structuralTerm(Node term) throws IOException {
            if (!"term".equals(term.tag) || term.scalars.size() < 6) {
                throw uncheckable("recursive term key received a malformed term");
            }
            List<String> scalars = new ArrayList<>();
            scalars.add(term.scalars.get(2));
            scalars.add(term.scalars.get(3));
            scalars.add(term.scalars.get(4));
            scalars.add(term.scalars.get(5));
            scalars.addAll(term.scalars.subList(6, term.scalars.size()));
            List<Node> children = new ArrayList<>();
            for (Node child : term.children) {
                Node referenced = terms.get(scalar(child, 0));
                if (referenced == null) {
                    throw uncheckable("recursive term key has a dangling child");
                }
                children.add(structuralTerm(referenced));
            }
            return node("term-key/" + term.scalars.get(1), scalars, children);
        }

        private Node proof(
                String variant,
                Node context,
                String sortKind,
                String sortValue,
                Node left,
                Node right,
                List<Node> premises,
                Node payload) throws IOException {
            Node record = withContentId(
                    "proof",
                    List.of(
                            variant,
                            scalar(context, 0),
                            sortKind,
                            sortValue,
                            scalar(left, 0),
                            scalar(right, 0)),
                    List.of(
                            node("premises", premises.stream()
                                    .map(proof -> leaf("proof-ref", scalar(proof, 0)))
                                    .toList()),
                            payload));
            return internContent(proofs, record, "proof");
        }

        private void witness(Node witness) throws IOException {
            internNamed(witnesses, scalar(witness, 0), witness, "witness");
        }

        private Node snapshot(
                long revision,
                String status,
                List<Node> classes,
                List<Node> parents,
                List<Node> shapes,
                List<Node> hashes,
                List<Node> parentUses,
                List<Node> symmetries,
                List<Node> dirty) throws IOException {
            Node record = withContentId(
                    "snapshot",
                    List.of(Long.toString(revision), status),
                    List.of(
                            sortedSection("classes", classes),
                            sortedSection("parents", parents),
                            sortedSection("shapes", shapes),
                            sortedPairSection("hash-cons", hashes),
                            sortedPairSection("parent-uses", parentUses),
                            sortedSection("symmetries", symmetries),
                            node(
                                    "maintenance",
                                    List.of(
                                            sortedSection(
                                                    "retirements", List.of()),
                                            sortedSection("dirty", dirty)))));
            return internContent(snapshots, record, "snapshot");
        }

        private void event(Node event) {
            events.add(event);
        }

        private Node canonical(Node record) throws IOException {
            return internContent(
                    canonicalRecords, record, "canonical record");
        }

        private Node unfolding(Node record) throws IOException {
            return internContent(unfoldings, record, "unfolding");
        }

        private Node section(String tag, Map<String, Node> values) {
            return node(tag, new ArrayList<>(values.values()));
        }

        private Node sortedSection(String tag, List<Node> values) {
            List<Node> sorted = new ArrayList<>(values);
            sorted.sort(Comparator.comparing(value -> scalar(value, 0)));
            return node(tag, sorted);
        }

        private Node sortedPairSection(String tag, List<Node> values) {
            List<Node> sorted = new ArrayList<>(values);
            sorted.sort(Comparator
                    .comparing((Node value) -> scalar(value, 0))
                    .thenComparing(value -> scalar(value, 1)));
            return node(tag, sorted);
        }

        private static Node internContent(
                Map<String, Node> target,
                Node record,
                String kind) throws IOException {
            return internNamed(target, scalar(record, 0), record, kind);
        }

        private static Node internNamed(
                Map<String, Node> target,
                String id,
                Node record,
                String kind) throws IOException {
            Node prior = target.putIfAbsent(id, record);
            if (prior != null && !prior.equals(record)) {
                throw uncheckable(kind + " ID collision at " + id);
            }
            return prior == null ? record : prior;
        }
    }

    private static final class InsertionWire {
        private final CertifiedInsertionResult insertion;
        private final String witnessId;
        private final String eclassId;
        private final String shapeId;
        private final Node source;
        private final Node kernel;
        private final Node replay;
        @SuppressWarnings("unused")
        private final InsertionEvidence evidence;
        private Node orbit;
        private Node fresh;
        @SuppressWarnings("unused")
        private Node canonical;

        private InsertionWire(
                CertifiedInsertionResult insertion,
                String witnessId,
                String eclassId,
                String shapeId,
                Node source,
                Node kernel,
                Node replay,
                InsertionEvidence evidence) {
            this.insertion = insertion;
            this.witnessId = witnessId;
            this.eclassId = eclassId;
            this.shapeId = shapeId;
            this.source = source;
            this.kernel = kernel;
            this.replay = replay;
            this.evidence = evidence;
        }
    }

    private static final class StepCursor {
        private final List<FiniteUnfoldingStepIndex> steps;
        private int index;

        private StepCursor(List<FiniteUnfoldingStepIndex> steps) {
            this.steps = steps;
        }

        private FiniteUnfoldingStepIndex next() throws IOException {
            if (index >= steps.size()) {
                throw uncheckable("unfolding index trace ended early");
            }
            return steps.get(index++);
        }

        private boolean exhausted() {
            return index == steps.size();
        }
    }

    private record Node(String tag, List<String> scalars, List<Node> children) {
        private Node {
            Objects.requireNonNull(tag, "tag");
            scalars = List.copyOf(scalars);
            children = List.copyOf(children);
        }
    }

    private record InsertionEvidence(
            CertificateTraceEvent event,
            CertifiedInsertionResult insertion) {
    }

    private record Slice(
            List<CertificateTraceEvent> events,
            Map<EClassId, InsertionEvidence> insertions,
            Map<ParentRecordKey, InsertionEvidence> shapes,
            ParentEdgeCertificate union,
            CertifiedInsertionResult rootInsertion,
            FiniteUnfoldingTree unfolding) {
    }

    private record PathWire(
            List<Integer> path,
            String initialWitness,
            String leaderWitness,
            Node finalInvocation,
            List<Node> edgeProofs) {
    }

    private record InvocationOccurrence(
            List<Integer> path,
            TypedInvocation invocation) {
    }

    private record WirePortChild(String termId, PortValue value) {
    }

    private record TermAtPath(
            List<Integer> path,
            Node term,
            String operatorId,
            String schemaPath) {
    }

    private record ContainerNormalizationWire(List<Integer> path, Node proof) {
    }

    private record SerializedOrbitCandidate(
            TypedENode node,
            TypedRenaming witness,
            StructuralKey completeOrder) {
        private SerializedOrbitCandidate {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(witness, "witness");
            Objects.requireNonNull(completeOrder, "completeOrder");
        }

        private static int compare(
                SerializedOrbitCandidate left,
                SerializedOrbitCandidate right) {
            return left.completeOrder().compareTo(right.completeOrder());
        }
    }

    private record PortDepth(PortValue port, int depth) {
        private PortDepth {
            Objects.requireNonNull(port, "port");
        }
    }

    private record OrbitSummary(
            TypedENode minimum,
            TypedRenaming minimumWitness,
            long candidateCount,
            List<Node> freeRenamingReferences) {
        private OrbitSummary {
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(minimumWitness, "minimumWitness");
            freeRenamingReferences = List.copyOf(freeRenamingReferences);
            if (candidateCount <= 0L) {
                throw new IllegalArgumentException(
                        "A canonical orbit must contain at least identity");
            }
        }
    }

    private static int compareNodes(Node left, Node right) {
        int compared = left.tag().compareTo(right.tag());
        if (compared != 0) {
            return compared;
        }
        compared = compareStrings(left.scalars(), right.scalars());
        if (compared != 0) {
            return compared;
        }
        int shared = Math.min(left.children().size(), right.children().size());
        for (int index = 0; index < shared; index++) {
            compared = compareNodes(
                    left.children().get(index), right.children().get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.children().size(), right.children().size());
    }

    private static int compareStrings(List<String> left, List<String> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int compared = left.get(index).compareTo(right.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private record RepWire(Node rep, Node invocation, Node normalized) {
    }
}
