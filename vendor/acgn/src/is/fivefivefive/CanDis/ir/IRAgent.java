package is.fivefivefive.CanDis.ir;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.ConstSymbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.alloy.SigSymbol;
import is.fivefivefive.ACGN.alloy.VarSymbol;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.QuantiVar;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.NormalForm.TemporalOp;
import is.fivefivefive.CanDis.theory.SemanticProfile;

public class IRAgent {
    private static final AtomicLong NEXT_TEMPORAL_PARSER_OCCURRENCE_ID =
            new AtomicLong(1L);

    /**
     * One-use evidence that a temporal source came from this IRAgent's MASG
     * traversal. Its constructor is private so metadata-only clients cannot
     * manufacture temporal-reference authority.
     */
    public static final class TemporalReferenceEvidence {
        private final Multigraph graph;
        private final SemanticProfile semanticProfile;
        private final AugmentedNode parserSource;
        private final EGraphNode source;
        private final NormalForm owner;
        private final List<MASGEdge> downlinks;
        private final AugmentedNode graphRoot;
        private final List<MASGEdge> sourcePathEdges;
        private final int sourceVisit;
        private final int childIndex;
        private final int arity;
        private final Opcode sourceOpcode;
        private final long sourceOccurrenceLineage;
        private final long parserOccurrenceId;
        private final String sourceName;
        private final String sourceType;
        private final ExactAlloyType exactType;
        private boolean consumed;

        private TemporalReferenceEvidence(
                IRAgent issuer,
                AugmentedNode parserSource,
                int sourceVisit,
                List<MASGEdge> downlinks,
                EGraphNode source,
                NormalForm owner,
                int childIndex,
                int arity) {
            IRAgent checkedIssuer = java.util.Objects.requireNonNull(issuer, "issuer");
            this.graph = java.util.Objects.requireNonNull(
                    checkedIssuer.graph, "source graph");
            this.semanticProfile = checkedIssuer.semanticProfile;
            this.parserSource = java.util.Objects.requireNonNull(
                    parserSource, "parser temporal source");
            this.source = java.util.Objects.requireNonNull(source, "temporal source");
            this.owner = java.util.Objects.requireNonNull(owner, "temporal owner");
            this.downlinks = List.copyOf(
                    java.util.Objects.requireNonNull(downlinks, "temporal downlinks"));
            this.graphRoot = java.util.Objects.requireNonNull(
                    graph.getRoot(), "source graph root");
            this.sourcePathEdges = identityPath(
                    graphRoot, parserSource, graph.getEdges());
            this.sourceVisit = sourceVisit;
            this.childIndex = childIndex;
            this.arity = arity;
            this.sourceOpcode = source.getOpcode();
            this.sourceOccurrenceLineage = source.getSourceOccurrenceLineage();
            this.sourceName = source.getSourceName();
            this.sourceType = source.getSourceType();
            this.exactType = source.getExactAlloyType();
            this.parserOccurrenceId = NEXT_TEMPORAL_PARSER_OCCURRENCE_ID.getAndIncrement();
            if (!containsIdentity(checkedIssuer.nfs, owner)) {
                throw new IllegalStateException(
                        "Temporal owner is detached from its IRAgent traversal");
            }
            requireValid(false);
        }

        /** Consumes this parser-owned occurrence exactly once for its owner. */
        public synchronized TemporalReferenceClaim consumeFor(NormalForm requestedOwner) {
            if (consumed) {
                throw new IllegalStateException(
                        "Temporal parser occurrence evidence is single-use");
            }
            if (requestedOwner != owner) {
                throw new IllegalArgumentException(
                        "Temporal parser occurrence evidence belongs to another owner");
            }
            requireValid(true);
            consumed = true;
            return new TemporalReferenceClaim(
                    this,
                    owner,
                    source,
                    sourceOpcode,
                    sourceOccurrenceLineage,
                    parserOccurrenceId,
                    childIndex,
                    arity);
        }

        private void requireValid(boolean consuming) {
            if (sourceVisit <= 0 || childIndex < 0 || arity <= 0
                    || sourceOccurrenceLineage <= 0L || parserOccurrenceId <= 0L
                    || graph.getRoot() != graphRoot
                    || !containsIdentity(graph.getVertices(), graphRoot)
                    || !containsIdentity(graph.getVertices(), parserSource)
                    || !pathRemainsValid()) {
                throw new IllegalStateException(
                        "Temporal evidence is detached from its IRAgent traversal");
            }
            Opcode parserOpcode = opcodeOf(parserSource);
            int parserArity;
            TemporalOp[] binary = temporalOpsOf(parserSource);
            if (binary != null) {
                parserArity = binary.length;
            } else if (unaryTemporalOpOf(parserOpcode) != null) {
                parserArity = 1;
            } else {
                throw new IllegalArgumentException(
                        "Parser occurrence is not a temporal operator");
            }
            if (parserOpcode != sourceOpcode || parserArity != arity
                    || downlinks.size() != arity || !source.getChildren().isEmpty()
                    || source.getOpcode() != sourceOpcode
                    || source.getSourceOccurrenceLineage() != sourceOccurrenceLineage
                    || source.getMetatype() != Metatype.BOOLEAN
                    || !source.getSemanticProfile().equals(semanticProfile)
                    || source.getExactAlloyType() == null
                    || source.getExactAlloyType().kind() != ExactAlloyType.Kind.BOOL
                    || exactType == null || exactType.kind() != ExactAlloyType.Kind.BOOL
                    || !source.getExactAlloyType().sameOccurrenceEvidenceAs(exactType)
                    || !java.util.Objects.equals(sourceName, source.getSourceName())
                    || !java.util.Objects.equals(sourceType, source.getSourceType())) {
                throw new IllegalStateException(
                        "Temporal source changed or disagrees with its parser occurrence");
            }
            Symbol parserSymbol = parserSource.getSymbol();
            if (parserSymbol == null
                    || !java.util.Objects.equals(sourceName, parserSymbol.getName())
                    || !java.util.Objects.equals(sourceType, parserSymbol.getType())) {
                throw new IllegalStateException(
                        "Temporal source metadata disagrees with its parser symbol");
            }
            ExactAlloyType parserType = parserSource.getExactType(graph, sourceVisit);
            if (parserType == null || !parserType.sameOccurrenceEvidenceAs(exactType)) {
                throw new IllegalStateException(
                        "Temporal source lacks parser-concordant exact Boolean type evidence");
            }
            List<MASGEdge> sourceVisitEdges =
                    parserSource.getDownlinksAtTimeOfVisit(graph, sourceVisit);
            if (sourceVisitEdges == null || sourceVisitEdges.size() != arity
                    || sourceVisitEdges.size() != downlinks.size()) {
                throw new IllegalStateException(
                        "Temporal source visit has no exact child-edge bucket");
            }
            boolean[] positions = new boolean[arity];
            for (MASGEdge edge : downlinks) {
                if (edge == null || edge.getSource() != parserSource
                        || edge.getTimeOfVisit() != sourceVisit
                        || edge.getPosition() <= 0 || edge.getPosition() > arity
                        || positions[edge.getPosition() - 1]
                        || !containsIdentity(sourceVisitEdges, edge)
                        || !containsIdentity(graph.getVertices(), edge.getTarget())
                        || !containsOccurrenceEdge(graph.getEdges(), edge)) {
                    throw new IllegalStateException(
                            "Temporal child edge lacks parser-occurrence provenance");
                }
                positions[edge.getPosition() - 1] = true;
            }
            for (MASGEdge edge : sourceVisitEdges) {
                if (!containsIdentity(downlinks, edge)) {
                    throw new IllegalStateException(
                            "Temporal source visit contains an uncommitted child edge");
                }
            }
            if (consuming && childIndex + arity > owner.getTemporalChildren().size()) {
                throw new IllegalStateException(
                        "Temporal parser occurrence has no complete child phase group");
            }
        }

        private synchronized boolean remainsValidFor(NormalForm requestedOwner) {
            if (!consumed || requestedOwner != owner) {
                return false;
            }
            try {
                requireValid(true);
                return true;
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                return false;
            }
        }

        private boolean pathRemainsValid() {
            AugmentedNode current = graphRoot;
            if (sourcePathEdges.isEmpty()) {
                return current == parserSource;
            }
            for (MASGEdge edge : sourcePathEdges) {
                if (edge == null || edge.getSource() != current
                        || !containsIdentity(graph.getEdges(), edge)
                        || !containsIdentity(graph.getVertices(), edge.getTarget())) {
                    return false;
                }
                current = edge.getTarget();
            }
            return current == parserSource;
        }

        private static List<MASGEdge> identityPath(
                AugmentedNode root,
                AugmentedNode target,
                List<MASGEdge> edges) {
            if (root == target) {
                return List.of();
            }
            java.util.IdentityHashMap<AugmentedNode, List<MASGEdge>> outgoing =
                    new java.util.IdentityHashMap<>();
            for (MASGEdge edge : edges) {
                outgoing.computeIfAbsent(
                        edge.getSource(), ignored -> new ArrayList<>()).add(edge);
            }
            java.util.IdentityHashMap<AugmentedNode, MASGEdge> predecessor =
                    new java.util.IdentityHashMap<>();
            java.util.Set<AugmentedNode> seen =
                    java.util.Collections.newSetFromMap(
                            new java.util.IdentityHashMap<>());
            java.util.ArrayDeque<AugmentedNode> pending = new java.util.ArrayDeque<>();
            seen.add(root);
            pending.add(root);
            while (!pending.isEmpty() && !seen.contains(target)) {
                AugmentedNode current = pending.removeFirst();
                for (MASGEdge edge : outgoing.getOrDefault(current, List.of())) {
                    AugmentedNode next = edge.getTarget();
                    if (seen.add(next)) {
                        predecessor.put(next, edge);
                        pending.addLast(next);
                    }
                }
            }
            if (!seen.contains(target)) {
                throw new IllegalStateException(
                        "Temporal parser occurrence is unreachable from the MASG root");
            }
            List<MASGEdge> reversed = new ArrayList<>();
            for (AugmentedNode current = target; current != root; ) {
                MASGEdge edge = predecessor.get(current);
                if (edge == null) {
                    throw new IllegalStateException(
                            "Temporal parser occurrence path is incomplete");
                }
                reversed.add(edge);
                current = edge.getSource();
            }
            java.util.Collections.reverse(reversed);
            return List.copyOf(reversed);
        }

        private static boolean containsIdentity(Iterable<?> values, Object target) {
            if (values == null) {
                return false;
            }
            for (Object value : values) {
                if (value == target) {
                    return true;
                }
            }
            return false;
        }

        private static boolean containsOccurrenceEdge(
                Iterable<MASGEdge> values,
                MASGEdge target) {
            if (values == null || target == null) {
                return false;
            }
            for (MASGEdge value : values) {
                if (value != null
                        && value.getSource() == target.getSource()
                        && value.getTarget() == target.getTarget()
                        && value.getPosition() == target.getPosition()
                        && value.getTimeOfVisit() == target.getTimeOfVisit()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Immutable data transferred across the parser/normal-form boundary. */
    public static final class TemporalReferenceClaim {
        private TemporalReferenceEvidence evidence;
        private final NormalForm owner;
        private EGraphNode source;
        private final Opcode sourceOpcode;
        private final long sourceOccurrenceLineage;
        private final long parserOccurrenceId;
        private final int childIndex;
        private final int arity;
        private boolean sealed;

        private TemporalReferenceClaim(
                TemporalReferenceEvidence evidence,
                NormalForm owner,
                EGraphNode source,
                Opcode sourceOpcode,
                long sourceOccurrenceLineage,
                long parserOccurrenceId,
                int childIndex,
                int arity) {
            this.evidence = evidence;
            this.owner = owner;
            this.source = source;
            this.sourceOpcode = sourceOpcode;
            this.sourceOccurrenceLineage = sourceOccurrenceLineage;
            this.parserOccurrenceId = parserOccurrenceId;
            this.childIndex = childIndex;
            this.arity = arity;
        }

        public synchronized EGraphNode source() {
            if (sealed || source == null) {
                throw new IllegalStateException(
                        "A sealed temporal occurrence no longer exposes parser state");
            }
            return source;
        }

        public Opcode sourceOpcode() {
            return sourceOpcode;
        }

        public long sourceOccurrenceLineage() {
            return sourceOccurrenceLineage;
        }

        public long parserOccurrenceId() {
            return parserOccurrenceId;
        }

        public int childIndex() {
            return childIndex;
        }

        public int arity() {
            return arity;
        }

        public synchronized boolean remainsValidFor(NormalForm requestedOwner) {
            if (requestedOwner != owner) {
                return false;
            }
            return sealed || (evidence != null && evidence.remainsValidFor(owner));
        }

        /**
         * Revalidates the live source once, then drops all parser-graph references.
         * The owner-bound immutable claim remains sufficient after preparation.
         */
        public synchronized void sealFor(NormalForm requestedOwner) {
            if (requestedOwner != owner) {
                throw new IllegalArgumentException(
                        "Temporal occurrence claim belongs to another owner");
            }
            if (sealed) {
                return;
            }
            if (evidence == null || !evidence.remainsValidFor(owner)) {
                throw new IllegalStateException(
                        "Temporal occurrence changed before its snapshot was sealed");
            }
            evidence = null;
            source = null;
            sealed = true;
        }
    }

    @FunctionalInterface
    public interface DiagnosticsObserver {
        void onStage(String stage, NormalForm activeNormalForm, List<NormalForm> normalForms);
    }

    private static final DiagnosticsObserver NO_OBSERVER = (stage, active, normalForms) -> { };

    private Multigraph graph;
    private final SemanticProfile semanticProfile;
    
    private List<NormalForm> nfs; // the normal forms from the graph in temporal logical operators order; 
    // try to normalize as much as possible from MASG to the normal form. Try prenexing. 

    public IRAgent(Multigraph graph) {
        this(graph, SemanticProfile.alloyOverflowForbidding());
    }

    public IRAgent(Multigraph graph, SemanticProfile semanticProfile) {
        this.graph = graph;
        this.semanticProfile = java.util.Objects.requireNonNull(
                semanticProfile, "semanticProfile");
        if (!this.semanticProfile.isAdmissibleAlloyProfile()) {
            throw new IllegalArgumentException(
                    "IRAgent requires an admitted Alloy semantic profile");
        }
        this.nfs = new ArrayList<>();
    }

    public List<NormalForm> normalForms() {
        return nfs;
    }

    public void computeNormalForm() {
        computeNormalForm(NO_OBSERVER);
    }

    public void computeNormalForm(DiagnosticsObserver observer) {
        DiagnosticsObserver stages = observer == null ? NO_OBSERVER : observer;
        nfs.clear();
        AugmentedNode root = graph.getRoot();
        if (root == null) {
            return;
        }
        EGraphNode.beginGraph();
        try {
            NormalForm rootNf = new NormalForm();
            nfs.add(rootNf);
            Map<AugmentedNode, Integer> tovTracker = new IdentityHashMap<>();
            int[] nextId = new int[] { 0 };
            stages.onStage("begin-temporal-skeleton", rootNf, nfs);
            rootNf.addEClass(buildEGraph(
                    root,
                    nextTov(tovTracker, root),
                    rootNf,
                    tovTracker,
                    nextId,
                    new HashSet<>(),
                    new IdentityHashMap<>()));
            stages.onStage("temporal-skeleton", rootNf, nfs);
            assignTemporalPhasePaths(rootNf, "phase[0]");
            normalizeTemporalTree(
                    rootNf,
                    new HashMap<>(),
                    java.util.Collections.emptyList(),
                    new int[] { 0 },
                    stages);
        } finally {
            stages.onStage("begin-reachable-egraph", null, nfs);
            List<EGraphNode> roots = new ArrayList<>();
            for (NormalForm normalForm : nfs) {
                if (normalForm.getMatrixEGraph() != null) {
                    roots.add(normalForm.getMatrixEGraph());
                }
                EGraphNode certificationRoot = normalForm.getCertificationMatrixEGraph();
                if (certificationRoot != null
                        && certificationRoot != normalForm.getMatrixEGraph()) {
                    roots.add(certificationRoot);
                }
            }
            EGraphNode.retainReachable(roots);
            stages.onStage("reachable-egraph", null, nfs);
            EGraphNode.endGraph();
        }
    }

    private EGraphNode buildEGraph(
            AugmentedNode node,
            int tov,
            NormalForm nf,
            Map<AugmentedNode, Integer> tovTracker,
            int[] nextId,
            Set<String> activePath,
            Map<AugmentedNode, EGraphNode> letBindings) {
        Opcode opcode = opcodeOf(node);
        ExactAlloyType exactType = node.getExactType(graph, tov);
        String activeKey = node.hashCode() + "@" + tov;
        List<MASGEdge> downlinks = downlinksFor(node, tov, opcode);
        int currentId = nextId[0]++;
        Symbol sourceSymbol = node.getSymbol();
        EGraphNode current;
        if (opcode == Opcode.GLOBALBINDING
                && sourceSymbol instanceof SigSymbol
                && isBuiltinSetSymbol((SigSymbol) sourceSymbol)) {
            current = EGraphNode.builtinSetConstant(
                    currentId,
                    (SigSymbol) sourceSymbol,
                    exactType,
                    semanticProfile);
        } else if (opcode == Opcode.CONSTANT
                && sourceSymbol instanceof ConstSymbol
                && ((ConstSymbol) sourceSymbol).isBuiltinIdentityRelation()) {
            current = EGraphNode.builtinIdentityRelation(
                    currentId,
                    (ConstSymbol) sourceSymbol,
                    exactType,
                    semanticProfile);
        } else {
            current = new EGraphNode(
                    currentId, opcode, new ArrayList<>(), isCommutative(opcode),
                    maxArity(node, opcode), isFlexibleArity(opcode),
                    metatypeOf(node, opcode, exactType),
                    semanticProfile);
        }
        attachSourceMetadata(current, node, exactType);

        if (opcode == Opcode.LET && (downlinks == null || downlinks.isEmpty())) {
            EGraphNode replacement = letBindings.get(node);
            if (replacement != null) {
                return replacement;
            }
        }

        if (!activePath.add(activeKey)) {
            return current;
        }
        try {
        if (downlinks == null || downlinks.isEmpty()) {
            return current;
        }

        if (opcode == Opcode.LET) {
            List<MASGEdge> semanticChildren = new ArrayList<>(2);
            for (MASGEdge downlink : downlinks) {
                if (opcodeOf(downlink.getTarget()) != Opcode.END) {
                    semanticChildren.add(downlink);
                }
            }
            if (semanticChildren.size() != 2) {
                throw new IllegalStateException(
                        "LET requires exactly one bound expression and one body, found "
                                + semanticChildren.size());
            }
            AugmentedNode boundSource = semanticChildren.get(0).getTarget();
            EGraphNode bound = buildEGraph(
                    boundSource,
                    nextTov(tovTracker, boundSource),
                    nf,
                    tovTracker,
                    nextId,
                    activePath,
                    letBindings);
            Map<AugmentedNode, EGraphNode> bodyBindings =
                    new IdentityHashMap<>(letBindings);
            bodyBindings.put(node, bound);
            AugmentedNode bodySource = semanticChildren.get(1).getTarget();
            return buildEGraph(
                    bodySource,
                    nextTov(tovTracker, bodySource),
                    nf,
                    tovTracker,
                    nextId,
                    activePath,
                    bodyBindings);
        }

        if (opcode == Opcode.CALL) {
            CallSymbol call = requireCallSymbol(node);
            for (int index = 1; index <= call.getDeclaredArity(); index++) {
                AugmentedNode argument = downlinks.get(index).getTarget();
                current.addChild(buildEGraph(
                        argument,
                        nextTov(tovTracker, argument),
                        nf,
                        tovTracker,
                        nextId,
                        activePath,
                        letBindings));
            }
            return current;
        }

        TemporalOp[] temporalOps = temporalOpsOf(node);
        if (temporalOps != null && downlinks.size() >= 2) {
            int temporalIndex = nf.getTemporalChildren().size();
            NormalForm leftNf = new NormalForm(
                    nf, temporalOps[0], nextId[0]++, semanticProfile);
            NormalForm rightNf = new NormalForm(
                    nf, temporalOps[1], nextId[0]++, semanticProfile);
            nf.addTemporalChild(leftNf);
            nf.addTemporalChild(rightNf);
            nfs.add(leftNf);
            nfs.add(rightNf);
            addTemporalChild(
                    leftNf, downlinks.get(0).getTarget(), tovTracker, nextId,
                    activePath, letBindings);
            addTemporalChild(
                    rightNf, downlinks.get(1).getTarget(), tovTracker, nextId,
                    activePath, letBindings);
            return nf.createTemporalReference(new TemporalReferenceEvidence(
                    this, node, tov, downlinks, current, nf, temporalIndex, 2));
        }
        TemporalOp unaryTemporalOp = unaryTemporalOpOf(opcode);
        if (unaryTemporalOp != null && !downlinks.isEmpty()) {
            int temporalIndex = nf.getTemporalChildren().size();
            NormalForm temporalNf = new NormalForm(
                    nf, unaryTemporalOp, nextId[0]++, semanticProfile);
            nf.addTemporalChild(temporalNf);
            nfs.add(temporalNf);
            addTemporalChild(
                    temporalNf, downlinks.get(0).getTarget(), tovTracker, nextId,
                    activePath, letBindings);
            return nf.createTemporalReference(new TemporalReferenceEvidence(
                    this, node, tov, downlinks, current, nf, temporalIndex, 1));
        }

        for (MASGEdge downlink : downlinks) {
            AugmentedNode child = downlink.getTarget();
            current.addChild(buildEGraph(
                    child,
                    nextTov(tovTracker, child),
                    nf,
                    tovTracker,
                    nextId,
                    activePath,
                    letBindings));
        }
        return current;
        } finally {
            activePath.remove(activeKey);
        }
    }

    private void normalizeTemporalTree(
            NormalForm normalForm,
            Map<String, QuantiVar> inherited,
            List<NormalForm.PhaseLocalBindingImport> phaseLocalImports,
            int[] nextVarId,
            DiagnosticsObserver observer) {
        normalForm.installPhaseLocalBindingImports(phaseLocalImports);
        Map<String, QuantiVar> visible = new HashMap<>(inherited);
        for (NormalForm.PhaseLocalBindingImport imported : phaseLocalImports) {
            QuantiVar variable = imported.variable();
            visible.put(variable.getName(), variable);
            visible.put(variable.getDeBruijnKey(), variable);
            for (String alias : variable.getOriginalNames()) {
                visible.put(alias, variable);
            }
        }
        normalForm.normalize(visible, nextVarId,
                (stage, active) -> observer.onStage(stage, active, nfs));
        observer.onStage("begin-temporal-negation", normalForm, nfs);
        normalForm.pushTemporalNegations();
        observer.onStage("temporal-negation", normalForm, nfs);
        Map<String, QuantiVar> descendants = new HashMap<>(inherited);
        for (QuantiVar variable : normalForm.getParams()) {
            for (String alias : variable.getOriginalNames()) {
                descendants.put(alias, variable);
            }
        }
        for (QuantiVar variable : normalForm.getMatrixQuantiVars()) {
            for (String alias : variable.getOriginalNames()) {
                descendants.put(alias, variable);
            }
        }
        for (NormalForm child : normalForm.getTemporalChildren()) {
            normalizeTemporalTree(
                    child,
                    descendants,
                    normalForm.phaseLocalBindingImportsFor(child),
                    nextVarId,
                    observer);
        }
    }

    private static void assignTemporalPhasePaths(
            NormalForm normalForm,
            String path) {
        normalForm.assignPhasePath(path);
        List<NormalForm> children = normalForm.getTemporalChildren();
        for (int index = 0; index < children.size(); index++) {
            assignTemporalPhasePaths(
                    children.get(index),
                    path + "/temporal[" + index + "]");
        }
    }

    private List<MASGEdge> downlinksFor(AugmentedNode node, int tov, Opcode opcode) {
        int maxTov = graph.getTimeOfVisitMap().getOrDefault(node, tov);
        if (tov > maxTov) {
            if (opcode == Opcode.CALL) {
                throw new IllegalStateException(
                        "CALL occurrence was referenced more than once: "
                                + requireCallSymbol(node) + "@" + tov);
            }
            return null;
        }
        List<MASGEdge> downlinks = node.getDownlinksAtTimeOfVisit(graph, tov);
        if (opcode == Opcode.CALL) {
            return validateCallDownlinks(node, tov, downlinks);
        }
        int expected = expectedDownlinkCount(opcode);
        if (isQuantifierOpcode(opcode) && hasQuantifierBodyEdge(downlinks)) {
            return downlinks;
        }
        if (isQuantifierOpcode(opcode)) {
            List<MASGEdge> candidate = nearestQuantifierVisitWithBody(node, tov, maxTov);
            if (candidate != null) {
                return candidate;
            }
            return downlinks;
        }
        if (expected <= 0 || (downlinks != null && downlinks.size() == expected)) {
            return downlinks;
        }
        for (int candidateTov = Math.max(1, tov); candidateTov <= maxTov; candidateTov++) {
            List<MASGEdge> candidate = node.getDownlinksAtTimeOfVisit(graph, candidateTov);
            if (candidate != null && candidate.size() == expected) {
                return candidate;
            }
        }
        for (int candidateTov = Math.min(tov - 1, maxTov); candidateTov >= 1; candidateTov--) {
            List<MASGEdge> candidate = node.getDownlinksAtTimeOfVisit(graph, candidateTov);
            if (candidate != null && candidate.size() == expected) {
                return candidate;
            }
        }
        return downlinks;
    }

    private static List<MASGEdge> validateCallDownlinks(
            AugmentedNode node,
            int tov,
            List<MASGEdge> downlinks) {
        CallSymbol call = requireCallSymbol(node);
        int expected = call.getDeclaredArity() + 2;
        if (downlinks == null || downlinks.size() != expected) {
            throw new IllegalStateException(
                    "Incomplete CALL occurrence " + call + "@" + tov
                            + ": expected " + expected + " downlinks, found "
                            + (downlinks == null ? 0 : downlinks.size()));
        }
        List<MASGEdge> ordered = new ArrayList<>(downlinks);
        ordered.sort((left, right) -> Integer.compare(left.getPosition(), right.getPosition()));
        for (int index = 0; index < ordered.size(); index++) {
            MASGEdge edge = ordered.get(index);
            if (edge.getPosition() != index + 1
                    || edge.getTimeOfVisit() != tov
                    || edge.getSource() != node) {
                throw new IllegalStateException(
                        "CALL occurrence has noncontiguous roles: " + call + "@" + tov);
            }
        }
        Symbol callee = ordered.get(0).getTarget().getSymbol();
        if (!call.matchesTarget(callee)) {
            throw new IllegalStateException(
                    "CALL occurrence has the wrong callee: " + call + "@" + tov);
        }
        for (int index = 1; index < expected - 1; index++) {
            Symbol argument = ordered.get(index).getTarget().getSymbol();
            if (argument != null && argument.isEndSymbol()) {
                throw new IllegalStateException(
                        "CALL occurrence contains END in an argument role: " + call + "@" + tov);
            }
        }
        Symbol terminator = ordered.get(expected - 1).getTarget().getSymbol();
        if (terminator == null || !terminator.isEndSymbol()) {
            throw new IllegalStateException(
                    "CALL occurrence has no final terminator: " + call + "@" + tov);
        }
        return ordered;
    }

    private static CallSymbol requireCallSymbol(AugmentedNode node) {
        if (!(node.getSymbol() instanceof CallSymbol)) {
            throw new IllegalStateException(
                    "CALL nodes require explicit callee/arity identity, found " + node.getSymbol());
        }
        return (CallSymbol) node.getSymbol();
    }

    private List<MASGEdge> nearestQuantifierVisitWithBody(AugmentedNode node, int tov, int maxTov) {
        List<MASGEdge> best = null;
        int bestScore = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int candidateTov = 1; candidateTov <= maxTov; candidateTov++) {
            List<MASGEdge> candidate = node.getDownlinksAtTimeOfVisit(graph, candidateTov);
            int score = quantifierBodyScore(candidate);
            if (score <= 0) {
                continue;
            }
            int distance = Math.abs(candidateTov - tov);
            if (score > bestScore || (score == bestScore && distance < bestDistance)) {
                best = candidate;
                bestScore = score;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean hasQuantifierBodyEdge(List<MASGEdge> downlinks) {
        return quantifierBodyScore(downlinks) > 0;
    }

    private static int quantifierBodyScore(List<MASGEdge> downlinks) {
        if (downlinks == null) {
            return 0;
        }
        int score = 0;
        for (MASGEdge edge : downlinks) {
            Opcode childOpcode = opcodeOf(edge.getTarget());
            if (childOpcode != Opcode.END && !isRelDeclOpcode(childOpcode)) {
                score = Math.max(score, quantifierBodyOpcodeScore(childOpcode));
            }
        }
        return score;
    }

    private static int quantifierBodyOpcodeScore(Opcode opcode) {
        switch (opcode) {
            case IMPLIES:
            case IFF:
            case AND:
            case OR:
            case FORALL:
            case EXISTS:
            case NO:
            case ONE:
            case LONE:
                return 4;
            case NOT:
            case SOME:
            case IN:
            case NOT_IN:
            case EQUALS:
            case NOT_EQUALS:
            case GT:
            case GTE:
            case LT:
            case LTE:
                return 2;
            default:
                return 1;
        }
    }

    private static boolean isQuantifierOpcode(Opcode opcode) {
        return opcode == Opcode.FORALL || opcode == Opcode.EXISTS || opcode == Opcode.NO
                || opcode == Opcode.LONE || opcode == Opcode.ONE || opcode == Opcode.SUM
                || opcode == Opcode.COMPREHENSION;
    }

    private static int expectedDownlinkCount(Opcode opcode) {
        if (opcode == Opcode.ITE) {
            return 3;
        }
        if (isFormulaBinary(opcode)) {
            return 2;
        }
        return -1;
    }

    private void attachSourceMetadata(
            EGraphNode eGraphNode,
            AugmentedNode sourceNode,
            ExactAlloyType exactType) {
        Symbol symbol = sourceNode.getSymbol();
        if (symbol == null) {
            return;
        }
        if (symbol instanceof SigSymbol
                && isBuiltinSetSymbol((SigSymbol) symbol)) {
            return;
        }
        if (symbol instanceof ConstSymbol
                && ((ConstSymbol) symbol).isBuiltinIdentityRelation()) {
            return;
        }
        if (symbol instanceof CallSymbol) {
            CallSymbol call = (CallSymbol) symbol;
            eGraphNode.setSourceName(call.getSourceName());
            eGraphNode.setSemanticIdentity(call.getCallee());
            eGraphNode.setCallOccurrenceId(call.getOccurrenceId());
            eGraphNode.setDeclaredArity(call.getDeclaredArity());
            eGraphNode.setCallArityAuthority(call.getArityAuthority().name());
        } else {
            eGraphNode.setSourceName(symbol.getName());
            if (symbol instanceof SigSymbol) {
                eGraphNode.setSemanticIdentity(
                        ((SigSymbol) symbol).getSemanticIdentity());
            }
        }
        eGraphNode.setSourceType(symbol.getType());
        eGraphNode.setExactAlloyType(exactType);
        if (eGraphNode.getOpcode() == Opcode.VARIABLE) {
            if (symbol instanceof VarSymbol) {
                String lexicalIdentity = ((VarSymbol) symbol).getHashName();
                eGraphNode.setSemanticIdentity(lexicalIdentity);
                eGraphNode.setAlphaName(lexicalIdentity);
            } else {
                eGraphNode.setAlphaName(symbol.getName());
            }
        }
        if (symbol instanceof SigSymbol
                && ((SigSymbol) symbol).hasParserSignatureAuthority()) {
            eGraphNode.attachParserSignatureEvidence((SigSymbol) symbol);
        }
    }

    private static boolean isBuiltinSetSymbol(SigSymbol symbol) {
        return symbol.getKind() == SigSymbol.Kind.BUILTIN_NONE
                || symbol.getKind() == SigSymbol.Kind.BUILTIN_UNIV;
    }

    private void addTemporalChild(
            NormalForm nf,
            AugmentedNode child,
            Map<AugmentedNode, Integer> tovTracker,
            int[] nextId,
            Set<String> activePath,
            Map<AugmentedNode, EGraphNode> letBindings) {
        nf.getMatrixEGraph().addChild(buildEGraph(
                child,
                nextTov(tovTracker, child),
                nf,
                tovTracker,
                nextId,
                activePath,
                letBindings));
    }

    private static int nextTov(Map<AugmentedNode, Integer> tovTracker, AugmentedNode node) {
        int tov = tovTracker.getOrDefault(node, 0) + 1;
        tovTracker.put(node, tov);
        return tov;
    }

    private static Opcode opcodeOf(AugmentedNode node) {
        Symbol symbol = node.getSymbol();
        if (symbol != null) {
            switch (symbol.getClass().getSimpleName()) {
                case "AssertSymbol":
                    return Opcode.ASSERTION;
                case "CheckSymbol":
                    return Opcode.CHECK;
                case "RunSymbol":
                    return Opcode.RUN;
                case "FactSymbol":
                case "ExtFact":
                    return Opcode.FACT;
                case "LetSymbol":
                    return Opcode.LET;
                case "PredRootSymbol":
                    return Opcode.PREDICATE;
                case "FunRootSymbol":
                    return Opcode.FUNCTION;
                case "SigSymbol":
                case "FieldRelation":
                case "SubsetRelation":
                    return Opcode.GLOBALBINDING;
                case "VarSymbol":
                    return Opcode.VARIABLE;
                case "ConstSymbol":
                    return Opcode.CONSTANT;
                case "DummySymbol":
                    return Opcode.DUMMY;
                case "RefSymbol":
                    return Opcode.REF;
                case "ShadowSymbol":
                    return Opcode.SHADOW;
                case "EndSymbol":
                    return Opcode.END;
                case "DeclRootSymbol":
                    return relDeclOpcode(node);
                default:
                    break;
            }
        }

        if (node.getSyntactic() == -127) {
            return relDeclOpcode(node);
        }

        if (node.getSyntactic() == 2 || node.getSyntactic() == -2) {
            return Opcode.ITE;
        }

        if (node.getSyntactic() == 3) {
            switch ((int) Math.round(node.getSemantic())) {
                case 1:
                    return Opcode.SUM;
                case 2:
                    return Opcode.COMPREHENSION;
                default:
                    return Opcode.COMPREHENSION;
            }
        }

        if (node.getSyntactic() == -3) {
            switch ((int) Math.round(node.getSemantic())) {
                case 1:
                    return Opcode.FORALL;
                case 2:
                    return Opcode.EXISTS;
                case 3:
                    return Opcode.NO;
                case 4:
                    return Opcode.LONE;
                case 5:
                    return Opcode.ONE;
                default:
                    return Opcode.FORALL;
            }
        }

        if (node.getSyntactic() == 7 || node.getSyntactic() == -7) {
            return Opcode.CALL;
        }

        if (node.getSyntactic() == 4) {
            switch ((int) Math.round(node.getSemantic())) {
                case 1:
                    return Opcode.DISJOINT;
                case 2:
                    return Opcode.TOTALORDER_LIST;
                default:
                    return Opcode.LIST;
            }
        }

        if (node.getSyntactic() == -4) {
            return ((int) Math.round(node.getSemantic())) == 2 ? Opcode.OR : Opcode.AND;
        }

        if (node.getSyntactic() == 5 || node.getSyntactic() == 15) {
            return binaryExprOpcode(node);
        }

        if (node.getSyntactic() == -5) {
            return binaryFormulaOpcode(node);
        }

        if (node.getSyntactic() == 6 || node.getSyntactic() == 16) {
            return unaryExprOpcode(node);
        }

        if (node.getSyntactic() == -6) {
            return unaryFormulaOpcode(node);
        }

        if (node.getSyntactic() == -128) {
            return Opcode.SHADOW;
        }

        return Opcode.PREDICATE;
    }

    private static Opcode relDeclOpcode(AugmentedNode node) {
        switch ((int) Math.round(node.getSemantic())) {
            case 1:
                return Opcode.DISJ;
            case 2:
                return Opcode.VAR;
            case 3:
                return Opcode.DISJVAR;
            default:
                return Opcode.GENERICRELDECL;
        }
    }

    private static Opcode binaryFormulaOpcode(AugmentedNode node) {
        switch ((int) Math.round(node.getSemantic())) {
            case 1:
                return Opcode.EQUALS;
            case 2:
                return Opcode.NOT_EQUALS;
            case 3:
                return Opcode.AND;
            case 4:
                return Opcode.GT;
            case 5:
                return Opcode.GTE;
            case 6:
                return Opcode.IFF;
            case 7:
                return Opcode.IMPLIES;
            case 8:
                return Opcode.IN;
            case 9:
                return Opcode.LT;
            case 10:
                return Opcode.LTE;
            case 11:
                return Opcode.NOT_GT;
            case 12:
                return Opcode.NOT_GTE;
            case 13:
                return Opcode.NOT_IN;
            case 14:
                return Opcode.NOT_LT;
            case 15:
                return Opcode.NOT_LTE;
            case 16:
                return Opcode.OR;
            case 17:
                return Opcode.RELEASES;
            case 18:
                return Opcode.SINCE;
            case 19:
                return Opcode.TRIGGERED;
            case 20:
                return Opcode.UNTIL;
            default:
                return Opcode.PREDICATE;
        }
    }

    private static Opcode binaryExprOpcode(AugmentedNode node) {
        switch ((int) Math.round(node.getSemantic())) {
            case 1:
                return Opcode.ARROW;
            case 2:
                return Opcode.ANY_ARROW_SOME;
            case 3:
                return Opcode.ANY_ARROW_ONE;
            case 4:
                return Opcode.ANY_ARROW_LONE;
            case 5:
                return Opcode.SOME_ARROW_ANY;
            case 6:
                return Opcode.SOME_ARROW_SOME;
            case 7:
                return Opcode.SOME_ARROW_ONE;
            case 8:
                return Opcode.SOME_ARROW_LONE;
            case 9:
                return Opcode.ONE_ARROW_ANY;
            case 10:
                return Opcode.ONE_ARROW_SOME;
            case 11:
                return Opcode.ONE_ARROW_ONE;
            case 12:
                return Opcode.ONE_ARROW_LONE;
            case 13:
                return Opcode.LONE_ARROW_ANY;
            case 14:
                return Opcode.LONE_ARROW_SOME;
            case 15:
                return Opcode.LONE_ARROW_ONE;
            case 16:
                return Opcode.LONE_ARROW_LONE;
            case 17:
                return Opcode.ISSEQ_ARROW_LONE;
            case 18:
                return Opcode.JOIN;
            case 19:
                return Opcode.DOMAIN;
            case 20:
                return Opcode.RANGE;
            case 21:
                return Opcode.INTERSECT;
            case 22:
                return Opcode.PLUSPLUS;
            case 23:
                return Opcode.PLUS;
            case 24:
                return Opcode.IPLUS;
            case 25:
                return Opcode.MINUS;
            case 26:
                return Opcode.IMINUS;
            case 27:
                return Opcode.MUL;
            case 28:
                return Opcode.DIV;
            case 29:
                return Opcode.REM;
            case 30:
                return Opcode.SHL;
            case 31:
                return Opcode.SHA;
            case 32:
                return Opcode.SHR;
            default:
                return Opcode.FUNCTION;
        }
    }

    private static Opcode unaryExprOpcode(AugmentedNode node) {
        switch ((int) Math.round(node.getSemantic())) {
            case 1:
                return Opcode.SETOF;
            case 2:
                return Opcode.LONE;
            case 3:
                return Opcode.ONE;
            case 4:
                return Opcode.SOME;
            case 5:
                return Opcode.EXACTLY;
            case 6:
                return Opcode.TRANSPOSE;
            case 7:
                return Opcode.RCLOSURE;
            case 8:
                return Opcode.CLOSURE;
            case 9:
                return Opcode.CARDINALITY;
            case 10:
                return Opcode.CAST2INT;
            case 11:
                return Opcode.CAST2SIGINT;
            case 12:
                return Opcode.PRIME;
            default:
                return Opcode.FUNCTION;
        }
    }

    private static Opcode unaryFormulaOpcode(AugmentedNode node) {
        switch ((int) Math.round(node.getSemantic())) {
            case 1:
                return Opcode.LONE;
            case 2:
                return Opcode.ONE;
            case 3:
                return Opcode.SOME;
            case 4:
                return Opcode.NO;
            case 5:
                return Opcode.NOT;
            case 6:
                return Opcode.BEFORE;
            case 7:
                return Opcode.HISTORICALLY;
            case 8:
                return Opcode.ONCE;
            case 9:
                return Opcode.ALWAYS;
            case 10:
                return Opcode.EVENTUALLY;
            case 11:
                return Opcode.AFTER;
            default:
                return Opcode.PREDICATE;
        }
    }

    private static TemporalOp[] temporalOpsOf(AugmentedNode node) {
        if (node.getSyntactic() != -5) {
            return null;
        }
        switch ((int) Math.round(node.getSemantic())) {
            case 17:
                return new TemporalOp[] { TemporalOp.RELEASESL, TemporalOp.RELEASESR };
            case 18:
                return new TemporalOp[] { TemporalOp.SINCEL, TemporalOp.SINCER };
            case 19:
                return new TemporalOp[] { TemporalOp.TRIGGEREDL, TemporalOp.TRIGGEREDR };
            case 20:
                return new TemporalOp[] { TemporalOp.UNTILL, TemporalOp.UNTILR };
            default:
                return null;
        }
    }

    private static TemporalOp unaryTemporalOpOf(Opcode opcode) {
        switch (opcode) {
            case BEFORE:
                return TemporalOp.BEFORE;
            case HISTORICALLY:
                return TemporalOp.HISTORICALLY;
            case ONCE:
                return TemporalOp.ONCE;
            case ALWAYS:
                return TemporalOp.ALWAYS;
            case EVENTUALLY:
                return TemporalOp.EVENTUALLY;
            case AFTER:
                return TemporalOp.AFTER;
            default:
                return null;
        }
    }

    private static boolean isCommutative(Opcode opcode) {
        return opcode == Opcode.AND || opcode == Opcode.OR || opcode == Opcode.IFF
                || opcode == Opcode.EQUALS || opcode == Opcode.NOT_EQUALS
                || opcode == Opcode.INTERSECT || opcode == Opcode.PLUS || opcode == Opcode.MUL
                || opcode == Opcode.IPLUS
                || opcode == Opcode.DISJOINT;
    }

    private static int maxArity(AugmentedNode node, Opcode opcode) {
        if (opcode == Opcode.CALL) {
            return requireCallSymbol(node).getDeclaredArity();
        }
        if (isFlexibleArity(opcode)) {
            return -1;
        }
        if (isLeaf(opcode)) {
            return 0;
        }
        if (isUnary(opcode)) {
            return 1;
        }
        if (opcode == Opcode.ITE) {
            return 3;
        }
        return 2;
    }

    private static boolean isLeaf(Opcode opcode) {
        return opcode == Opcode.VARIABLE
                || opcode == Opcode.GLOBALBINDING
                || opcode == Opcode.CONSTANT
                || opcode == Opcode.REF
                || opcode == Opcode.SHADOW
                || opcode == Opcode.END;
    }

    private static boolean isFlexibleArity(Opcode opcode) {
        return isAssociative(opcode) || opcode == Opcode.LIST
                || opcode == Opcode.DISJOINT || opcode == Opcode.DISJOINT_LIST
                || opcode == Opcode.TOTALORDER_LIST
                || opcode == Opcode.FORALL || opcode == Opcode.EXISTS || opcode == Opcode.NO
                || opcode == Opcode.LONE || opcode == Opcode.ONE || opcode == Opcode.COMPREHENSION
                || opcode == Opcode.SUM
                || isRelDeclOpcode(opcode);
    }

    private static boolean isAssociative(Opcode opcode) {
        return is.fivefivefive.CanDis.core.AlloyOperatorPolicy.isFlatSetOperator(opcode);
    }

    private static boolean isUnary(Opcode opcode) {
        return opcode == Opcode.NOT || opcode == Opcode.SOME || opcode == Opcode.NO || opcode == Opcode.LONE
                || opcode == Opcode.ONE || opcode == Opcode.SETOF || opcode == Opcode.EXACTLY
                || opcode == Opcode.TRANSPOSE || opcode == Opcode.RCLOSURE || opcode == Opcode.CLOSURE
                || opcode == Opcode.CARDINALITY || opcode == Opcode.CAST2INT || opcode == Opcode.CAST2SIGINT
                || opcode == Opcode.PRIME || opcode == Opcode.BEFORE || opcode == Opcode.HISTORICALLY
                || opcode == Opcode.ONCE || opcode == Opcode.ALWAYS || opcode == Opcode.EVENTUALLY
                || opcode == Opcode.AFTER;
    }

    private static boolean isFormulaBinary(Opcode opcode) {
        switch (opcode) {
            case AND:
            case OR:
            case IMPLIES:
            case IFF:
            case EQUALS:
            case NOT_EQUALS:
            case IN:
            case NOT_IN:
            case GT:
            case GTE:
            case LT:
            case LTE:
            case NOT_GT:
            case NOT_GTE:
            case NOT_LT:
            case NOT_LTE:
            case RELEASES:
            case SINCE:
            case TRIGGERED:
            case UNTIL:
                return true;
            default:
                return false;
        }
    }

    private static boolean isRelDeclOpcode(Opcode opcode) {
        return opcode == Opcode.DISJ || opcode == Opcode.VAR || opcode == Opcode.DISJVAR
                || opcode == Opcode.GENERICRELDECL;
    }

    private static Metatype metatypeOf(
            AugmentedNode node,
            Opcode opcode,
            ExactAlloyType exactType) {
        if (opcode == Opcode.CONSTANT
                && exactType != null
                && exactType.kind() == ExactAlloyType.Kind.BOOL) {
            return Metatype.BOOLEAN;
        }
        if (opcode == Opcode.VARIABLE || opcode == Opcode.GLOBALBINDING
                || opcode == Opcode.CONSTANT) {
            return Metatype.ATOMIC;
        }
        if (isRelDeclOpcode(opcode)) {
            return Metatype.CONTROL;
        }
        if (exactType != null) {
            switch (exactType.kind()) {
                case BOOL:
                    return Metatype.BOOLEAN;
                case INT:
                case RELATION:
                case EMPTY_RELATION:
                    return Metatype.SET;
                case UNKNOWN:
                default:
                    break;
            }
        }
        if (opcode == Opcode.EQUALS || opcode == Opcode.NOT_EQUALS || opcode == Opcode.GT || opcode == Opcode.GTE
                || opcode == Opcode.IN || opcode == Opcode.LT || opcode == Opcode.LTE || opcode == Opcode.NOT_GT
                || opcode == Opcode.NOT_GTE || opcode == Opcode.NOT_IN || opcode == Opcode.NOT_LT
                || opcode == Opcode.NOT_LTE || opcode == Opcode.SOME || opcode == Opcode.NO || opcode == Opcode.NOT
                || opcode == Opcode.BEFORE || opcode == Opcode.HISTORICALLY || opcode == Opcode.ONCE
                || opcode == Opcode.ALWAYS || opcode == Opcode.EVENTUALLY || opcode == Opcode.AFTER
                || opcode == Opcode.AND || opcode == Opcode.OR || opcode == Opcode.IMPLIES || opcode == Opcode.IFF
                || opcode == Opcode.DISJOINT || opcode == Opcode.DISJOINT_LIST
                || opcode == Opcode.RELEASES || opcode == Opcode.SINCE || opcode == Opcode.TRIGGERED
                || opcode == Opcode.UNTIL || opcode == Opcode.PREDICATE) {
            return Metatype.BOOLEAN;
        }
        return node.getSyntactic() > 0 ? Metatype.SET : Metatype.BOOLEAN;
    }
}
