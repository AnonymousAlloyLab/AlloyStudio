package is.fivefivefive.CanDis.core;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.theory.LeanVerifiedRewrite;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.core.QuantiVar.Cardinality;
import is.fivefivefive.CanDis.core.QuantiVar.Quantifier;

import java.util.HashMap;

/**
 * This class encodes the normal form of a formula or function, which consists of a flat prenex binding list and a matrix e-graph representation of the formula. 
 * The normal form can be used for distance calculation, as well as for other analyses and transformations on the formula.
 * It is the locus of control for the visitor that generates the normal form from the original formula. 
 */
public class NormalForm {
    private static final AtomicLong NEXT_TEMPORAL_REFERENCE_AUTHORITY_ID =
            new AtomicLong(1L);
    @FunctionalInterface
    public interface NormalizationObserver {
        void onStage(String stage, NormalForm normalForm);
    }

    private static final NormalizationObserver NO_OBSERVER = (stage, normalForm) -> { };

    // matrix e-graph representation of the formula, where each node is a subformula, and edges represent the structure of the formula.
    private EGraphNode matrixEGraphRoot;
    // Post-prenex/NNF source tree retained before ACI folding for certified law replay.
    private EGraphNode certificationMatrixEGraphRoot;
    private List<QuantiVar> params; // the parameters of the formula or function, in the order they appear in the original formula or function declaration.
    private List<QuantiVar> matrixQuantiVars; // the quantified variables in the matrix, in the order they appear in the formula.
    private List<QuantiVar> inheritedQuantiVars; // bindings owned by ancestor temporal phases and visible in this matrix.
    private List<PhaseLocalBindingImport> phaseLocalBindingImports;
    private final Map<NormalForm, List<PhaseLocalBindingImport>> temporalChildBindingImports;
    private final Map<String, LocalBindingSeed> localBindingSeeds;
    private String phasePath;
    private List<NormalForm> temporalChildren;
    private final Map<Long, TemporalReferenceAuthority> temporalReferenceAuthorities;
    private final Object lifecycleLock;
    private TemporalOp temporalOp; // the temporal operator of the formula, if any, e.g., "before", "historically", "once", "always", "eventually", "until", "releases", "since", "triggered". If none, then it is a non-temporal formula.
    private int nextDisjointnessClass;
    private int prenexReductionsThisPass;
    private volatile boolean frozenForCertification;
    public enum TemporalOp {
        NONE,
        BEFORE,
        HISTORICALLY,
        ONCE,
        ALWAYS,
        EVENTUALLY,
        AFTER,
        UNTILL,
        UNTILR,
        RELEASESL,
        RELEASESR,
        SINCEL,
        SINCER,
        TRIGGEREDL,
        TRIGGEREDR
    }

    /**
     * Owner-bound provenance for one lexical binder imported into another
     * temporal phase. The source lineage authenticates the declaration; the
     * phase paths and owner node prevent same-spelled binders in another scope
     * from being substituted for it.
     */
    public static final class PhaseLocalBindingImport {
        private final QuantiVar variable;
        private final NormalForm ownerPhase;
        private final EGraphNode ownerBinder;
        private final long sourceBinderLineage;
        private final String ownerPhasePath;
        private final String targetPhasePath;
        private final String binderContext;

        private PhaseLocalBindingImport(
                QuantiVar variable,
                NormalForm ownerPhase,
                EGraphNode ownerBinder,
                long sourceBinderLineage,
                String ownerPhasePath,
                String targetPhasePath,
                String binderContext) {
            this.variable = java.util.Objects.requireNonNull(variable, "phase-local variable");
            this.ownerPhase = java.util.Objects.requireNonNull(ownerPhase, "phase-local owner");
            this.ownerBinder = java.util.Objects.requireNonNull(ownerBinder, "phase-local binder");
            if (sourceBinderLineage <= 0L) {
                throw new IllegalArgumentException(
                        "A phase-local import requires positive source binder lineage");
            }
            this.sourceBinderLineage = sourceBinderLineage;
            this.ownerPhasePath = requirePhasePath(ownerPhasePath);
            this.targetPhasePath = requirePhasePath(targetPhasePath);
            if (binderContext == null || binderContext.isBlank()) {
                throw new IllegalArgumentException(
                        "A phase-local import requires its lexical binder context");
            }
            this.binderContext = binderContext;
        }

        public QuantiVar variable() {
            return variable;
        }

        public NormalForm ownerPhase() {
            return ownerPhase;
        }

        public EGraphNode ownerBinder() {
            return ownerBinder;
        }

        public long sourceBinderLineage() {
            return sourceBinderLineage;
        }

        public String ownerPhasePath() {
            return ownerPhasePath;
        }

        public String targetPhasePath() {
            return targetPhasePath;
        }

        public String binderContext() {
            return binderContext;
        }

        private PhaseLocalBindingImport retarget(String targetPath) {
            return new PhaseLocalBindingImport(
                    variable,
                    ownerPhase,
                    ownerBinder,
                    sourceBinderLineage,
                    ownerPhasePath,
                    targetPath,
                    binderContext);
        }

        private static String requirePhasePath(String path) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("A temporal phase path must be nonempty");
            }
            return path;
        }
    }

    private static final class LocalBindingSeed {
        private final QuantiVar variable;
        private final long sourceBinderLineage;

        private LocalBindingSeed(QuantiVar variable, long sourceBinderLineage) {
            this.variable = java.util.Objects.requireNonNull(variable, "local variable");
            this.sourceBinderLineage = sourceBinderLineage;
        }
    }

    private static final class ScopedLocalBinding {
        private final QuantiVar variable;
        private final NormalForm ownerPhase;
        private final EGraphNode ownerBinder;
        private final long sourceBinderLineage;
        private final String ownerPhasePath;
        private final String binderContext;

        private ScopedLocalBinding(
                QuantiVar variable,
                NormalForm ownerPhase,
                EGraphNode ownerBinder,
                long sourceBinderLineage,
                String ownerPhasePath,
                String binderContext) {
            this.variable = variable;
            this.ownerPhase = ownerPhase;
            this.ownerBinder = ownerBinder;
            this.sourceBinderLineage = sourceBinderLineage;
            this.ownerPhasePath = ownerPhasePath;
            this.binderContext = binderContext;
        }

        private static ScopedLocalBinding from(PhaseLocalBindingImport imported) {
            return new ScopedLocalBinding(
                    imported.variable(),
                    imported.ownerPhase(),
                    imported.ownerBinder(),
                    imported.sourceBinderLineage(),
                    imported.ownerPhasePath(),
                    imported.binderContext());
        }

        private PhaseLocalBindingImport importInto(String targetPath) {
            return new PhaseLocalBindingImport(
                    variable,
                    ownerPhase,
                    ownerBinder,
                    sourceBinderLineage,
                    ownerPhasePath,
                    targetPath,
                    binderContext);
        }
    }

    public NormalForm() {
        // initialize the normal form with empty quantification tree and matrix e-graph, and empty parameter list and quantified variable list.
        this.matrixEGraphRoot = null;
        this.certificationMatrixEGraphRoot = null;
        this.params = new ArrayList<>();
        this.matrixQuantiVars = new ArrayList<>();
        this.inheritedQuantiVars = new ArrayList<>();
        this.phaseLocalBindingImports = new ArrayList<>();
        this.temporalChildBindingImports = new java.util.IdentityHashMap<>();
        this.localBindingSeeds = new HashMap<>();
        this.phasePath = "phase[0]";
        this.temporalChildren = new ArrayList<>();
        this.temporalReferenceAuthorities = new java.util.LinkedHashMap<>();
        this.lifecycleLock = new Object();
        this.temporalOp = TemporalOp.NONE;
        this.nextDisjointnessClass = 1;
    }

    public NormalForm(NormalForm parent, TemporalOp temporalOp, int egid) {
        this(parent, temporalOp, egid,
                childSemanticProfile(parent));
    }

    public NormalForm(
            NormalForm parent,
            TemporalOp temporalOp,
            int egid,
            SemanticProfile semanticProfile) {
        NormalForm checkedParent = java.util.Objects.requireNonNull(
                parent, "temporal parent");
        this.lifecycleLock = checkedParent.lifecycleLock;
        this.certificationMatrixEGraphRoot = null;
        this.params = new ArrayList<>();
        this.matrixQuantiVars = new ArrayList<>();
        this.inheritedQuantiVars = new ArrayList<>();
        this.phaseLocalBindingImports = new ArrayList<>();
        this.temporalChildBindingImports = new java.util.IdentityHashMap<>();
        this.localBindingSeeds = new HashMap<>();
        this.phasePath = "unassigned";
        this.temporalChildren = new ArrayList<>();
        this.temporalReferenceAuthorities = new java.util.LinkedHashMap<>();
        this.temporalOp = temporalOp;
        this.nextDisjointnessClass = 1;
        synchronized (lifecycleLock) {
            checkedParent.requireMutable();
            EGraphNode parentRoot = checkedParent.matrixEGraphRoot;
            this.matrixEGraphRoot = parentRoot == null
                    ? new EGraphNode(
                            egid, Opcode.TEMPORALROOT, new ArrayList<>(), false, 1,
                            false, Metatype.BOOLEAN, semanticProfile)
                    : EGraphNode.inOwningArena(
                            parentRoot,
                            egid,
                            Opcode.TEMPORALROOT,
                            new ArrayList<>(),
                            false,
                            1,
                            false,
                            Metatype.BOOLEAN,
                            semanticProfile);
            this.params.addAll(checkedParent.params);
        }
    }

    private static SemanticProfile childSemanticProfile(NormalForm parent) {
        NormalForm checkedParent = java.util.Objects.requireNonNull(
                parent, "temporal parent");
        synchronized (checkedParent.lifecycleLock) {
            checkedParent.requireMutable();
            return checkedParent.matrixEGraphRoot == null
                    ? SemanticProfile.alloyOverflowForbidding()
                    : checkedParent.matrixEGraphRoot.getSemanticProfile();
        }
    }

    public EGraphNode getMatrixEGraph() {
        synchronized (lifecycleLock) {
            return this.matrixEGraphRoot;
        }
    }
    public EGraphNode getCertificationMatrixEGraph() {
        synchronized (lifecycleLock) {
            return certificationMatrixEGraphRoot == null
                    ? matrixEGraphRoot : certificationMatrixEGraphRoot;
        }
    }
    public List<QuantiVar> getParams() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<>(this.params));
        }
    }
    public List<QuantiVar> getMatrixQuantiVars() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<>(this.matrixQuantiVars));
        }
    }
    public List<QuantiVar> getInheritedQuantiVars() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<>(this.inheritedQuantiVars));
        }
    }
    public List<QuantiVar> getLocalQuantiVars() {
        synchronized (lifecycleLock) {
            java.util.Set<QuantiVar> seen = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            List<QuantiVar> result = new ArrayList<>();
            for (LocalBindingSeed seed : localBindingSeeds.values()) {
                if (seen.add(seed.variable)) {
                    result.add(seed.variable);
                }
            }
            result.sort(java.util.Comparator.comparingInt(QuantiVar::getId));
            return Collections.unmodifiableList(result);
        }
    }
    public List<PhaseLocalBindingImport> getPhaseLocalBindingImports() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<>(phaseLocalBindingImports));
        }
    }
    public String getPhasePath() {
        synchronized (lifecycleLock) {
            return phasePath;
        }
    }

    /** Assigns the deterministic structural path before tree normalization. */
    public void assignPhasePath(String path) {
        synchronized (lifecycleLock) {
            requireMutable();
            String checked = PhaseLocalBindingImport.requirePhasePath(path);
            if (!"unassigned".equals(phasePath) && !phasePath.equals(checked)) {
                throw new IllegalStateException("A temporal phase received two structural paths");
            }
            phasePath = checked;
        }
    }

    /** Installs only imports issued by the exact structural parent reference. */
    public void installPhaseLocalBindingImports(
            List<PhaseLocalBindingImport> imports) {
        synchronized (lifecycleLock) {
            requireMutable();
            List<PhaseLocalBindingImport> checked = new ArrayList<>();
            java.util.Set<QuantiVar> seen = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            for (PhaseLocalBindingImport imported
                    : imports == null ? Collections.<PhaseLocalBindingImport>emptyList() : imports) {
                PhaseLocalBindingImport value = java.util.Objects.requireNonNull(
                        imported, "phase-local import");
                if (!phasePath.equals(value.targetPhasePath())) {
                    throw new IllegalArgumentException(
                            "A phase-local import targets another temporal phase");
                }
                if (!seen.add(value.variable())) {
                    throw new IllegalArgumentException(
                            "A temporal phase received the same local binder twice");
                }
                checked.add(value);
            }
            phaseLocalBindingImports = checked;
        }
    }

    public List<PhaseLocalBindingImport> phaseLocalBindingImportsFor(
            NormalForm child) {
        synchronized (lifecycleLock) {
            NormalForm checked = java.util.Objects.requireNonNull(child, "temporal child");
            if (!temporalChildren.contains(checked)) {
                throw new IllegalArgumentException(
                        "Phase-local imports can be requested only for a structural child");
            }
            List<PhaseLocalBindingImport> result = temporalChildBindingImports.get(checked);
            return result == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(result));
        }
    }
    public void addEClass(EGraphNode node) {
        synchronized (lifecycleLock) {
            requireMutable();
            if (this.matrixEGraphRoot == null) {
                this.matrixEGraphRoot = node;
            } else {
                this.matrixEGraphRoot.addChild(node);
            }
        }
    }
    public void addParam(QuantiVar param) {
        synchronized (lifecycleLock) {
            requireMutable();
            this.params.add(param);
        }
    }
    public void addMatrixQuantiVar(QuantiVar quantiVar) {
        synchronized (lifecycleLock) {
            requireMutable();
            this.matrixQuantiVars.add(quantiVar);
        }
    }
    public TemporalOp getTemporalOp() {
        synchronized (lifecycleLock) {
            return this.temporalOp;
        }
    }
    public List<NormalForm> getTemporalChildren() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<>(this.temporalChildren));
        }
    }
    public void addTemporalChild(NormalForm child) {
        synchronized (lifecycleLock) {
            requireMutable();
            NormalForm checked = java.util.Objects.requireNonNull(
                    child, "temporal child");
            if (checked.lifecycleLock != lifecycleLock) {
                throw new IllegalArgumentException(
                        "A temporal child must share its parent's lifecycle lock");
            }
            this.temporalChildren.add(checked);
        }
    }

    /** Lowers one parser occurrence after consuming IRAgent-owned evidence. */
    public EGraphNode createTemporalReference(
            IRAgent.TemporalReferenceEvidence evidence) {
        synchronized (lifecycleLock) {
        requireMutable();
        IRAgent.TemporalReferenceClaim claim =
                java.util.Objects.requireNonNull(evidence, "temporal evidence")
                        .consumeFor(this);
        EGraphNode source = claim.source();
        int childIndex = claim.childIndex();
        int arity = claim.arity();
        if (source == null || source.getMetatype() != Metatype.BOOLEAN
                || source.getExactAlloyType() == null
                || source.getExactAlloyType().kind()
                        != is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.BOOL) {
            throw new IllegalArgumentException(
                    "A temporal reference requires an exact Boolean source operator");
        }
        List<TemporalOp> expected = temporalOpsForSource(source.getOpcode());
        if (expected.size() != arity || childIndex < 0
                || childIndex + arity > temporalChildren.size()) {
            throw new IllegalArgumentException(
                    "Temporal reference arity or child range disagrees with its source operator");
        }
        List<NormalForm> children = new ArrayList<>(arity);
        for (int offset = 0; offset < arity; offset++) {
            NormalForm child = temporalChildren.get(childIndex + offset);
            if (child.temporalOp != expected.get(offset)) {
                throw new IllegalArgumentException(
                        "Temporal reference child operation disagrees with its source operator");
            }
            children.add(child);
        }
        long authorityId = NEXT_TEMPORAL_REFERENCE_AUTHORITY_ID.getAndIncrement();
        if (authorityId <= 0L) {
            throw new IllegalStateException("Temporal reference authority space exhausted");
        }
        EGraphNode reference = EGraphNode.inOwningArena(
                source,
                source.getId(),
                Opcode.REF,
                new ArrayList<>(),
                false,
                0,
                false,
                Metatype.BOOLEAN,
                source.getSemanticProfile());
        reference.setSourceName(temporalReferenceName(childIndex, arity));
        reference.setSourceType("Bool");
        reference.setExactAlloyType(
                is.fivefivefive.ACGN.alloy.ExactAlloyType.boolType());
        reference.attachTemporalReferenceAuthority(authorityId);
        TemporalReferenceAuthority prior = temporalReferenceAuthorities.put(
                authorityId,
                new TemporalReferenceAuthority(
                        authorityId,
                        this,
                        source.getOpcode(),
                        claim,
                        claim.sourceOccurrenceLineage(),
                        claim.parserOccurrenceId(),
                        childIndex,
                        children));
        if (prior != null) {
            throw new IllegalStateException("Duplicate temporal reference authority id");
        }
        return reference;
        }
    }

    /** Freezes the complete normalized source consumed by certification. */
    public void freezeForCertification() {
        synchronized (lifecycleLock) {
        if (frozenForCertification) {
            return;
        }
        requireAdmittedTemporalTree();
        for (QuantiVar parameter : params) {
            parameter.freezeForCertification();
        }
        for (QuantiVar variable : matrixQuantiVars) {
            variable.freezeForCertification();
        }
        for (QuantiVar variable : inheritedQuantiVars) {
            variable.freezeForCertification();
        }
        for (PhaseLocalBindingImport imported : phaseLocalBindingImports) {
            imported.variable().freezeForCertification();
        }
        for (NormalForm child : temporalChildren) {
            child.freezeForCertification();
        }
        if (matrixEGraphRoot != null) {
            admitAndFreezeTemporalReferences(matrixEGraphRoot, "live matrix");
        }
        if (certificationMatrixEGraphRoot != null) {
            admitAndFreezeTemporalReferences(
                    certificationMatrixEGraphRoot, "certification matrix");
        }
        sealTemporalReferenceEvidence();
        frozenForCertification = true;
        }
    }

    public boolean isFrozenForCertification() {
        return frozenForCertification;
    }

    /** Drops parser graph retention after one final owner-bound provenance check. */
    private void sealTemporalReferenceEvidence() {
        for (TemporalReferenceAuthority authority
                : temporalReferenceAuthorities.values()) {
            authority.seal();
        }
    }

    private void requireMutable() {
        if (frozenForCertification) {
            throw new IllegalStateException(
                    "A certified normalized form is immutable");
        }
    }

    /** Admits every matrix occurrence before any temporal phase can be rewritten. */
    public void requireAdmittedTemporalTree() {
        synchronized (lifecycleLock) {
        java.util.Set<NormalForm> admitted = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        java.util.Set<NormalForm> active = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        requireAdmittedTemporalTree(this, admitted, active);
        }
    }

    private static void requireAdmittedTemporalTree(
            NormalForm current,
            java.util.Set<NormalForm> admitted,
            java.util.Set<NormalForm> active) {
        if (admitted.contains(current)) {
            return;
        }
        if (!active.add(current)) {
            throw new IllegalStateException("Temporal normal forms must be acyclic");
        }
        if (current.matrixEGraphRoot != null) {
            current.matrixEGraphRoot.requireAdmittedGraph();
        }
        if (current.certificationMatrixEGraphRoot != null
                && current.certificationMatrixEGraphRoot
                        != current.matrixEGraphRoot) {
            current.certificationMatrixEGraphRoot.requireAdmittedGraph();
            current.requireAdmittedTemporalReferences(
                    current.certificationMatrixEGraphRoot,
                    "certification matrix");
        }
        if (current.matrixEGraphRoot != null) {
            current.requireAdmittedTemporalReferences(
                    current.matrixEGraphRoot, "live matrix");
        }
        current.requireCompleteTemporalChildCoverage();
        List<NormalForm> children = new ArrayList<>(current.temporalChildren);
        for (NormalForm child : children) {
            requireAdmittedTemporalTree(child, admitted, active);
        }
        active.remove(current);
        admitted.add(current);
    }

    private void requireAdmittedTemporalReferences(EGraphNode root, String label) {
        root.forEachAdmittedReachableNode(
                node -> admitTemporalReference(node, label));
    }

    private void admitAndFreezeTemporalReferences(EGraphNode root, String label) {
        root.admitAndFreezeForCertification(
                node -> admitTemporalReference(node, label));
    }

    /**
     * Validates every temporal reference that survives certified matrix
     * normalization. An issued authority need not survive: Boolean quotient
     * laws may prove its containing branch unreachable before certification.
     */
    private void admitTemporalReference(
            EGraphNode node,
            String label) {
        String sourceName = node.getSourceName();
        if (sourceName == null || !sourceName.startsWith("temporal[")) {
            return;
        }
        int[] target = temporalReferenceTarget(node);
        if (target == null) {
            throw new IllegalStateException(
                    "Malformed or non-REF temporal reference in " + label);
        }
        long authorityId = node.temporalReferenceAuthorityId();
        TemporalReferenceAuthority authority =
                temporalReferenceAuthorities.get(authorityId);
        if (authority == null || !authority.matches(node, target[0], target[1])) {
            throw new IllegalStateException(
                    "Temporal reference lacks owner-bound source authority: "
                            + sourceName);
        }
    }

    private void requireCompleteTemporalChildCoverage() {
        if (temporalChildren.isEmpty()) {
            if (!temporalReferenceAuthorities.isEmpty()) {
                throw new IllegalStateException(
                        "Temporal reference authority has no child phase");
            }
            return;
        }
        if (matrixEGraphRoot == null || temporalReferenceAuthorities.isEmpty()) {
            throw new IllegalStateException(
                    "A temporal child phase lacks an owner-issued matrix reference");
        }
        boolean[] covered = new boolean[temporalChildren.size()];
        for (TemporalReferenceAuthority authority
                : temporalReferenceAuthorities.values()) {
            if (authority.owner != this || authority.childIndex < 0
                    || authority.childIndex + authority.children.size()
                            > temporalChildren.size()) {
                throw new IllegalStateException(
                        "Temporal reference authority has an invalid child range");
            }
            for (int offset = 0; offset < authority.children.size(); offset++) {
                int index = authority.childIndex + offset;
                if (covered[index]
                        || temporalChildren.get(index) != authority.children.get(offset)) {
                    throw new IllegalStateException(
                            "Temporal child phases must have one exact owner reference");
                }
                covered[index] = true;
            }
        }
        for (boolean present : covered) {
            if (!present) {
                throw new IllegalStateException(
                        "A temporal child phase lacks an owner-issued matrix reference");
            }
        }
    }

    @LeanVerifiedRewrite("R0-CORE-010")
    public void pushTemporalNegations() {
        synchronized (lifecycleLock) {
        requireMutable();
        requireAdmittedTemporalTree();
        if (matrixEGraphRoot == null || temporalChildren.isEmpty()) {
            return;
        }
        boolean[] changed = new boolean[1];
        java.util.Set<String> dualizedReferences = new java.util.LinkedHashSet<>();
        List<TemporalDualization> stagedDualizations = new ArrayList<>();
        EGraphNode rewrittenMatrix = pushTemporalNegations(
                matrixEGraphRoot,
                changed,
                true,
                dualizedReferences,
                stagedDualizations);
        EGraphNode rewrittenCertification = certificationMatrixEGraphRoot;
        if (certificationMatrixEGraphRoot != null) {
            rewrittenCertification = pushTemporalNegations(
                    certificationMatrixEGraphRoot,
                    new boolean[1],
                    false,
                    dualizedReferences,
                    stagedDualizations);
        }
        if (changed[0] && rewrittenMatrix != null) {
            rewrittenMatrix.saturate();
        }
        for (TemporalDualization staged : stagedDualizations) {
            staged.commit();
        }
        matrixEGraphRoot = rewrittenMatrix;
        certificationMatrixEGraphRoot = rewrittenCertification;
        }
    }

    private EGraphNode pushTemporalNegations(
            EGraphNode node,
            boolean[] changed,
            boolean applyTemporalDualization,
            java.util.Set<String> dualizedReferences,
            List<TemporalDualization> stagedDualizations) {
        if (node == null) {
            return null;
        }
        if (node.getOpcode() == Opcode.NOT && node.getChildren().size() == 1) {
            EGraphNode reference = node.getChildren().get(0);
            int[] target = temporalReferenceTarget(reference);
            boolean dualized = target != null
                    && (applyTemporalDualization
                            ? (dualizedReferences.contains(reference.getSourceName())
                                    || stageTemporalChildren(
                                            target[0], target[1], stagedDualizations))
                            : dualizedReferences.contains(reference.getSourceName()));
            if (dualized) {
                if (applyTemporalDualization) {
                    dualizedReferences.add(reference.getSourceName());
                }
                changed[0] = true;
                return cloneEGraph(reference);
            }
        }
        EGraphNode rewritten = copyShallow(node, node.getOpcode());
        for (EGraphNode child : node.getChildren()) {
            EGraphNode rewrittenChild = pushTemporalNegations(
                    child,
                    changed,
                    applyTemporalDualization,
                    dualizedReferences,
                    stagedDualizations);
            if (rewrittenChild != null) {
                rewritten.addNormalizedChild(rewrittenChild);
            }
        }
        return rewritten;
    }

    private boolean stageTemporalChildren(
            int index,
            int arity,
            List<TemporalDualization> stagedDualizations) {
        if (index < 0 || arity < 1 || index + arity > temporalChildren.size()) {
            return false;
        }
        List<TemporalDualization> additions = new ArrayList<>(arity);
        for (int i = 0; i < arity; i++) {
            NormalForm child = temporalChildren.get(index + i);
            child.requireMutable();
            TemporalOp dual = temporalNegationDual(child.temporalOp);
            if (dual == null) {
                return false;
            }
            for (TemporalDualization staged : stagedDualizations) {
                if (staged.child == child) {
                    if (staged.temporalOp != dual) {
                        throw new IllegalStateException(
                                "A temporal child has contradictory staged duals");
                    }
                    dual = null;
                    break;
                }
            }
            if (dual != null) {
                additions.add(new TemporalDualization(
                        child,
                        dual,
                        negatedMatrixBeforeNormalization(child.matrixEGraphRoot)));
            }
        }
        stagedDualizations.addAll(additions);
        return true;
    }

    private static EGraphNode negatedMatrixBeforeNormalization(
            EGraphNode matrixEGraphRoot) {
        if (matrixEGraphRoot == null) {
            return null;
        }
        if (matrixEGraphRoot.getOpcode() != Opcode.TEMPORALROOT) {
            return syntheticUnary(
                    matrixEGraphRoot, Opcode.NOT, matrixEGraphRoot, -11);
        }
        EGraphNode root = copyShallow(matrixEGraphRoot, Opcode.TEMPORALROOT);
        List<EGraphNode> body = new ArrayList<>();
        for (EGraphNode child : matrixEGraphRoot.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                root.addNormalizedChild(child);
            } else {
                body.add(child);
            }
        }
        if (!body.isEmpty()) {
            EGraphNode matrix = body.size() == 1 ? body.get(0) : conjoin(null, body);
            root.addNormalizedChild(syntheticUnary(matrix, Opcode.NOT, matrix, -12));
        }
        return root;
    }

    private static final class TemporalDualization {
        private final NormalForm child;
        private final TemporalOp temporalOp;
        private final EGraphNode matrix;

        private TemporalDualization(
                NormalForm child,
                TemporalOp temporalOp,
                EGraphNode matrix) {
            this.child = child;
            this.temporalOp = temporalOp;
            this.matrix = matrix;
        }

        private void commit() {
            child.temporalOp = temporalOp;
            child.matrixEGraphRoot = matrix;
        }
    }

    private static int[] temporalReferenceTarget(EGraphNode node) {
        if (node.getOpcode() != Opcode.REF || node.getSourceName() == null) {
            return null;
        }
        String source = node.getSourceName();
        if (!source.startsWith("temporal[") || !source.endsWith("]")) {
            return null;
        }
        int colon = source.indexOf(':', 9);
        if (colon < 0) {
            return null;
        }
        try {
            int index = Integer.parseInt(source.substring(9, colon));
            int arity = Integer.parseInt(source.substring(colon + 1, source.length() - 1));
            return new int[] { index, arity };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String temporalReferenceName(int index, int arity) {
        return "temporal[" + index + ":" + arity + "]";
    }

    private static List<TemporalOp> temporalOpsForSource(Opcode opcode) {
        switch (opcode) {
            case BEFORE:
                return Collections.singletonList(TemporalOp.BEFORE);
            case HISTORICALLY:
                return Collections.singletonList(TemporalOp.HISTORICALLY);
            case ONCE:
                return Collections.singletonList(TemporalOp.ONCE);
            case ALWAYS:
                return Collections.singletonList(TemporalOp.ALWAYS);
            case EVENTUALLY:
                return Collections.singletonList(TemporalOp.EVENTUALLY);
            case AFTER:
                return Collections.singletonList(TemporalOp.AFTER);
            case UNTIL:
                return List.of(TemporalOp.UNTILL, TemporalOp.UNTILR);
            case RELEASES:
                return List.of(TemporalOp.RELEASESL, TemporalOp.RELEASESR);
            case SINCE:
                return List.of(TemporalOp.SINCEL, TemporalOp.SINCER);
            case TRIGGERED:
                return List.of(TemporalOp.TRIGGEREDL, TemporalOp.TRIGGEREDR);
            default:
                throw new IllegalArgumentException(
                        "Not a temporal source operator: " + opcode);
        }
    }

    private static final class TemporalReferenceAuthority {
        private final long authorityId;
        private final NormalForm owner;
        private final Opcode sourceOpcode;
        private final IRAgent.TemporalReferenceClaim sourceClaim;
        private final long sourceOccurrenceLineage;
        private final long parserOccurrenceId;
        private final int childIndex;
        private final List<NormalForm> children;

        private TemporalReferenceAuthority(
                long authorityId,
                NormalForm owner,
                Opcode sourceOpcode,
                IRAgent.TemporalReferenceClaim sourceClaim,
                long sourceOccurrenceLineage,
                long parserOccurrenceId,
                int childIndex,
                List<NormalForm> children) {
            this.authorityId = authorityId;
            this.owner = owner;
            this.sourceOpcode = sourceOpcode;
            this.sourceClaim = java.util.Objects.requireNonNull(
                    sourceClaim, "temporal source claim");
            this.sourceOccurrenceLineage = sourceOccurrenceLineage;
            this.parserOccurrenceId = parserOccurrenceId;
            this.childIndex = childIndex;
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }

        private boolean matches(EGraphNode reference, int index, int arity) {
            if (authorityId <= 0L || sourceOccurrenceLineage <= 0L
                    || parserOccurrenceId <= 0L || owner == null
                    || !sourceClaim.remainsValidFor(owner)
                    || reference.temporalReferenceAuthorityId() != authorityId
                    || reference.getOpcode() != Opcode.REF
                    || !reference.getChildren().isEmpty()
                    || reference.getMetatype() != Metatype.BOOLEAN
                    || reference.getExactAlloyType() == null
                    || reference.getExactAlloyType().kind()
                            != is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.BOOL
                    || !"Bool".equals(reference.getSourceType())
                    || !temporalReferenceName(childIndex, children.size())
                            .equals(reference.getSourceName())
                    || index != childIndex || arity != children.size()
                    || childIndex < 0
                    || childIndex + children.size() > owner.temporalChildren.size()) {
                return false;
            }
            List<TemporalOp> expected = temporalOpsForSource(sourceOpcode);
            boolean original = true;
            boolean dual = true;
            for (int offset = 0; offset < children.size(); offset++) {
                NormalForm child = owner.temporalChildren.get(childIndex + offset);
                if (child != children.get(offset)) {
                    return false;
                }
                TemporalOp operation = child.temporalOp;
                TemporalOp expectedOperation = expected.get(offset);
                original &= operation == expectedOperation;
                dual &= operation == temporalNegationDual(expectedOperation);
            }
            return original || dual;
        }

        private void seal() {
            sourceClaim.sealFor(owner);
        }
    }

    private static TemporalOp temporalNegationDual(TemporalOp op) {
        switch (op) {
            case ALWAYS:
                return TemporalOp.EVENTUALLY;
            case EVENTUALLY:
                return TemporalOp.ALWAYS;
            case HISTORICALLY:
                return TemporalOp.ONCE;
            case ONCE:
                return TemporalOp.HISTORICALLY;
            case UNTILL:
                return TemporalOp.RELEASESL;
            case UNTILR:
                return TemporalOp.RELEASESR;
            case RELEASESL:
                return TemporalOp.UNTILL;
            case RELEASESR:
                return TemporalOp.UNTILR;
            case SINCEL:
                return TemporalOp.TRIGGEREDL;
            case SINCER:
                return TemporalOp.TRIGGEREDR;
            case TRIGGEREDL:
                return TemporalOp.SINCEL;
            case TRIGGEREDR:
                return TemporalOp.SINCER;
            default:
                return null;
        }
    }
    public void prenex() {
        synchronized (lifecycleLock) {
            requireMutable();
            normalize();
        }
    }
    public void normalize() {
        synchronized (lifecycleLock) {
            requireMutable();
            normalize(new HashMap<>(), new int[] { 0 });
        }
    }
    public void normalize(Map<String, QuantiVar> inheritedBindings, int[] nextVarId) {
        synchronized (lifecycleLock) {
            requireMutable();
            normalize(inheritedBindings, nextVarId, NO_OBSERVER);
        }
    }
    public void normalize(
            Map<String, QuantiVar> inheritedBindings,
            int[] nextVarId,
            NormalizationObserver observer) {
        synchronized (lifecycleLock) {
        requireMutable();
        requireAdmittedTemporalTree();
        if (matrixEGraphRoot == null) {
            return;
        }
        NormalizationObserver stages = observer == null ? NO_OBSERVER : observer;
        matrixQuantiVars.clear();
        localBindingSeeds.clear();
        temporalChildBindingImports.clear();
        java.util.Set<QuantiVar> localImports = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (PhaseLocalBindingImport imported : phaseLocalBindingImports) {
            localImports.add(imported.variable());
        }
        inheritedQuantiVars = new ArrayList<>();
        for (QuantiVar inherited
                : new java.util.LinkedHashSet<>(inheritedBindings.values())) {
            if (!localImports.contains(inherited)) {
                inheritedQuantiVars.add(inherited);
            }
        }
        inheritedQuantiVars.sort(java.util.Comparator.comparingInt(QuantiVar::getId));
        nextDisjointnessClass = 1;
        matrixEGraphRoot = removeEndNodes(matrixEGraphRoot);
        Map<String, String> inheritedAlphaNames = new HashMap<>();
        for (Map.Entry<String, QuantiVar> entry : inheritedBindings.entrySet()) {
            inheritedAlphaNames.put(entry.getKey(), entry.getValue().getName());
        }
        stages.onStage("begin-alpha-beta-branch", this);
        matrixEGraphRoot = alphaRenameBoundVariables(
                matrixEGraphRoot, inheritedAlphaNames, new int[] { 0 });
        matrixEGraphRoot = betaRewriteLet(matrixEGraphRoot, new HashMap<>());
        matrixEGraphRoot = removeEndNodes(matrixEGraphRoot);
        matrixEGraphRoot = removeEndNodes(rewriteBranchConnectives(matrixEGraphRoot));
        stages.onStage("alpha-beta-branch", this);
        stages.onStage("begin-nnf", this);
        matrixEGraphRoot = removeEndNodes(toNNF(matrixEGraphRoot, false));
        matrixEGraphRoot = flattenBooleanAssociationForPrenex(matrixEGraphRoot);
        stages.onStage("nnf", this);
        stages.onStage("begin-prenex", this);
        Map<String, QuantiVar> prenexBindings = new HashMap<>(inheritedBindings);
        for (QuantiVar inherited : inheritedBindings.values()) {
            prenexBindings.put(inherited.getName(), inherited);
        }
        int remainingQuantifiers = countFormulaQuantifierNodes(matrixEGraphRoot);
        int maximumPrenexPasses = Math.max(
                1, Math.addExact(remainingQuantifiers, 1));
        // One pass is mandatory even without formula quantifiers because the
        // root's declaration children establish predicate/function parameters.
        boolean reachedPrenexFixedPoint = false;
        for (int pass = 0; pass < maximumPrenexPasses && !reachedPrenexFixedPoint; pass++) {
            prenexReductionsThisPass = 0;
            List<EGraphNode> constraints = new ArrayList<>();
            matrixEGraphRoot = prenex(
                    matrixEGraphRoot, prenexBindings, nextVarId, false, constraints,
                    "root", true, true, true, new PrenexSlotAllocator());
            matrixEGraphRoot = removeEndNodes(conjoin(matrixEGraphRoot, constraints));
            int nextRemaining = countFormulaQuantifierNodes(matrixEGraphRoot);
            if (prenexReductionsThisPass == 0 && nextRemaining != remainingQuantifiers) {
                throw new IllegalStateException(
                        "Prenex residual changed without a recorded reduction");
            }
            if (prenexReductionsThisPass > 0 && nextRemaining >= remainingQuantifiers) {
                throw new IllegalStateException(
                        "Recorded prenex reductions did not strictly decrease the residual");
            }
            reachedPrenexFixedPoint = nextRemaining == 0
                    || prenexReductionsThisPass == 0;
            if (!reachedPrenexFixedPoint) {
                // A local quantifier may have introduced a domain implication.
                // Eliminate that connective before another pass interprets
                // child polarity; otherwise its antecedent can be negated a
                // second time while an enclosing quantifier is lifted.
                matrixEGraphRoot = removeEndNodes(rewriteBranchConnectives(
                        matrixEGraphRoot));
                matrixEGraphRoot = removeEndNodes(toNNF(matrixEGraphRoot, false));
                matrixEGraphRoot = flattenBooleanAssociationForPrenex(
                        matrixEGraphRoot);
                int normalizedRemaining = countFormulaQuantifierNodes(
                        matrixEGraphRoot);
                if (normalizedRemaining != nextRemaining) {
                    throw new IllegalStateException(
                            "Inter-pass connective elimination changed the quantifier residual");
                }
            }
            remainingQuantifiers = nextRemaining;
        }
        if (!reachedPrenexFixedPoint) {
            throw new IllegalStateException(
                    "Prenexing did not reach a bounded structural fixed point");
        }
        minimizeOperationalCarriersFromPrefixWitnesses();
        stages.onStage("prenex", this);
        stages.onStage("begin-post-prenex-nnf", this);
        matrixEGraphRoot = removeEndNodes(rewriteBranchConnectives(matrixEGraphRoot));
        matrixEGraphRoot = removeEndNodes(toNNF(matrixEGraphRoot, false));
        matrixEGraphRoot = removeEndNodes(normalizeGuardedSourceRules(
                matrixEGraphRoot, guardedBindingFacts()));
        stages.onStage("post-prenex-nnf", this);
        matrixEGraphRoot.reseedSourceOccurrenceLineages();
        certificationMatrixEGraphRoot = cloneEGraph(matrixEGraphRoot);
        captureTemporalPhaseLocalBindings();
        stages.onStage("begin-aci", this);
        matrixEGraphRoot = normalizeAssociativeCommutative(matrixEGraphRoot);
        matrixEGraphRoot = removeEndNodes(matrixEGraphRoot);
        stages.onStage("aci", this);
        if (matrixEGraphRoot == null) {
            return;
        }
        stages.onStage("begin-saturation", this);
        matrixEGraphRoot.saturate();
        registerQuantifierSymmetries();
        stages.onStage("saturation", this);
        }
    }

    private void minimizeOperationalCarriersFromPrefixWitnesses() {
        java.util.Set<String> inhabited = new java.util.HashSet<>();
        recordDirectCarrierWitnesses(params, inhabited);
        recordDirectCarrierWitnesses(inheritedQuantiVars, inhabited);
        recordDirectCarrierWitnesses(phaseLocalVariables(), inhabited);
        for (QuantiVar variable : matrixQuantiVars) {
            String primitive = normalizeType(variable.getTypeName());
            String carrier = normalizeType(variable.getCarrierTypeName());
            if ("univ".equalsIgnoreCase(carrier) && inhabited.contains(primitive)) {
                variable.setCarrierTypeName(variable.getTypeName());
                carrier = primitive;
            }
            if (!primitive.isEmpty()
                    && primitive.equals(carrier)
                    && cardinalityGuaranteesNonempty(variable.getCardinality())) {
                inhabited.add(primitive);
            }
        }
    }

    private static void recordDirectCarrierWitnesses(
            List<QuantiVar> variables,
            java.util.Set<String> inhabited) {
        for (QuantiVar variable : variables) {
            String primitive = normalizeType(variable.getTypeName());
            if (!primitive.isEmpty()
                    && primitive.equals(normalizeType(variable.getCarrierTypeName()))
                    && cardinalityGuaranteesNonempty(variable.getCardinality())) {
                inhabited.add(primitive);
            }
        }
    }

    @LeanVerifiedRewrite("R0-STRUCT-001")
    private static EGraphNode alphaRenameBoundVariables(
            EGraphNode node,
            Map<String, String> scope,
            int[] nextBinderId) {
        if (node == null) {
            return null;
        }
        if (node.getOpcode() == Opcode.VARIABLE
                || (node.getOpcode() == Opcode.LET && node.getChildren().isEmpty())) {
            EGraphNode renamed = copyShallow(node, node.getOpcode());
            String alphaName = scope.get(bindingKey(node));
            if (alphaName == null) {
                alphaName = scope.get(node.getSourceName());
            }
            if (alphaName != null) {
                renamed.setAlphaName(alphaName);
            }
            return renamed;
        }
        if (node.getOpcode() == Opcode.LET && node.getChildren().size() >= 2) {
            EGraphNode renamed = copyShallow(node, Opcode.LET);
            renamed.addNormalizedChild(alphaRenameBoundVariables(node.getChildren().get(0), scope, nextBinderId));
            String alphaName = "@let:" + nextBinderId[0]++;
            renamed.setAlphaName(alphaName);
            Map<String, String> bodyScope = new HashMap<>(scope);
            if (node.getSourceName() != null) {
                bodyScope.put(node.getSourceName(), alphaName);
            }
            for (int i = 1; i < node.getChildren().size(); i++) {
                renamed.addNormalizedChild(alphaRenameBoundVariables(node.getChildren().get(i), bodyScope, nextBinderId));
            }
            return renamed;
        }
        if (bindsRelDeclarations(node)) {
            EGraphNode renamed = copyShallow(node, node.getOpcode());
            Map<String, String> bodyScope = new HashMap<>(scope);
            Map<Integer, EGraphNode> declarations = new HashMap<>();
            for (int i = 0; i < node.getChildren().size(); i++) {
                EGraphNode child = node.getChildren().get(i);
                if (isRelDecl(child.getOpcode())) {
                    declarations.put(i, alphaRenameRelDecl(child, bodyScope, nextBinderId));
                }
            }
            for (int i = 0; i < node.getChildren().size(); i++) {
                EGraphNode declaration = declarations.get(i);
                renamed.addNormalizedChild(declaration == null
                        ? alphaRenameBoundVariables(node.getChildren().get(i), bodyScope, nextBinderId)
                        : declaration);
            }
            return renamed;
        }
        EGraphNode renamed = copyShallow(node, node.getOpcode());
        for (EGraphNode child : node.getChildren()) {
            renamed.addNormalizedChild(alphaRenameBoundVariables(child, scope, nextBinderId));
        }
        return renamed;
    }

    @LeanVerifiedRewrite("R0-STRUCT-001")
    private static EGraphNode alphaRenameRelDecl(
            EGraphNode declaration,
            Map<String, String> bodyScope,
            int[] nextBinderId) {
        EGraphNode renamed = copyShallow(declaration, declaration.getOpcode());
        List<EGraphNode> children = declaration.getChildren();
        if (!children.isEmpty()) {
            renamed.addNormalizedChild(alphaRenameBoundVariables(children.get(0), bodyScope, nextBinderId));
        }
        for (int i = 1; i < children.size(); i++) {
            EGraphNode child = children.get(i);
            if (child.getOpcode() != Opcode.VARIABLE) {
                renamed.addNormalizedChild(alphaRenameBoundVariables(child, bodyScope, nextBinderId));
                continue;
            }
            EGraphNode variable = copyShallow(child, Opcode.VARIABLE);
            String alphaName = "@bind:" + nextBinderId[0]++;
            variable.setAlphaName(alphaName);
            renamed.addNormalizedChild(variable);
            String lexicalKey = bindingKey(child);
            if (lexicalKey != null) {
                bodyScope.put(lexicalKey, alphaName);
            }
            if (child.getSourceName() != null && lexicalKey == null) {
                bodyScope.put(child.getSourceName(), alphaName);
            }
        }
        return renamed;
    }

    private static boolean bindsRelDeclarations(EGraphNode node) {
        if (node.getOpcode() != Opcode.PREDICATE && node.getOpcode() != Opcode.FUNCTION
                && node.getOpcode() != Opcode.TEMPORALROOT && !isQuantifierNode(node)) {
            return false;
        }
        for (EGraphNode child : node.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                return true;
            }
        }
        return false;
    }

    private static String bindingKey(EGraphNode node) {
        return firstNonEmpty(node.getAlphaName(), node.getSourceName());
    }

    private void registerQuantifierSymmetries() {
        if (matrixEGraphRoot == null) {
            return;
        }
        for (int rightIndex = 1; rightIndex < matrixQuantiVars.size(); rightIndex++) {
            QuantiVar right = matrixQuantiVars.get(rightIndex);
            if (!isSymmetricBooleanQuantifier(right.getQuantifier())) {
                continue;
            }
            for (int leftIndex = rightIndex - 1; leftIndex >= 0; leftIndex--) {
                QuantiVar left = matrixQuantiVars.get(leftIndex);
                if (left.getQuantifier() != right.getQuantifier()) {
                    break;
                }
                if (samePermutationPayload(left, right)) {
                    matrixEGraphRoot.getEClass().addSlotSwap(left.getName(), right.getName());
                    break;
                }
            }
        }
    }

    private static boolean samePermutationPayload(QuantiVar left, QuantiVar right) {
        boolean exchangeableBlock = (left.getQuantifier() == Quantifier.ALL
                || left.getQuantifier() == Quantifier.SOME)
                || left.getBindingPath().equals(right.getBindingPath());
        return exchangeableBlock
                && left.getQuantifier() == right.getQuantifier()
                && left.getCardinality() == right.getCardinality()
                && left.getDisjointnessClass() == right.getDisjointnessClass()
                && normalizeType(left.getTypeName()).equals(normalizeType(right.getTypeName()))
                && normalizeType(left.getCarrierTypeName()).equals(
                        normalizeType(right.getCarrierTypeName()));
    }

    private static boolean isSymmetricBooleanQuantifier(Quantifier quantifier) {
        return quantifier == Quantifier.ALL || quantifier == Quantifier.SOME
                || quantifier == Quantifier.NO || quantifier == Quantifier.ONE
                || quantifier == Quantifier.LONE || quantifier == Quantifier.NOTONE
                || quantifier == Quantifier.NOTLONE;
    }

    @LeanVerifiedRewrite({"R0-CORE-004", "R0-CORE-005", "R0-CORE-006"})
    private static EGraphNode rewriteBranchConnectives(EGraphNode node) {
        if (node == null) {
            return null;
        }
        if (node.getOpcode() == Opcode.IMPLIES
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 2) {
            EGraphNode left = rewriteBranchConnectives(node.getChildren().get(0));
            EGraphNode right = rewriteBranchConnectives(node.getChildren().get(1));
            EGraphNode disjunction = syntheticNode(node, Opcode.OR, -1);
            disjunction.addNormalizedChild(syntheticUnary(node, Opcode.NOT, left, -2));
            disjunction.addNormalizedChild(right);
            return disjunction;
        }
        if (node.getOpcode() == Opcode.IFF
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 2) {
            EGraphNode left = rewriteBranchConnectives(node.getChildren().get(0));
            EGraphNode right = rewriteBranchConnectives(node.getChildren().get(1));

            EGraphNode leftImpliesRight = syntheticNode(node, Opcode.OR, -1);
            leftImpliesRight.addNormalizedChild(syntheticUnary(node, Opcode.NOT, cloneEGraph(left), -2));
            leftImpliesRight.addNormalizedChild(cloneEGraph(right));

            EGraphNode rightImpliesLeft = syntheticNode(node, Opcode.OR, -3);
            rightImpliesLeft.addNormalizedChild(syntheticUnary(node, Opcode.NOT, cloneEGraph(right), -4));
            rightImpliesLeft.addNormalizedChild(cloneEGraph(left));

            EGraphNode conjunction = syntheticNode(node, Opcode.AND, -5);
            conjunction.addNormalizedChild(leftImpliesRight);
            conjunction.addNormalizedChild(rightImpliesLeft);
            return conjunction;
        }
        if (node.getOpcode() == Opcode.ITE
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 3) {
            EGraphNode condition = rewriteBranchConnectives(node.getChildren().get(0));
            EGraphNode thenBranch = rewriteBranchConnectives(node.getChildren().get(1));
            EGraphNode elseBranch = rewriteBranchConnectives(node.getChildren().get(2));

            EGraphNode thenCase = syntheticNode(node, Opcode.AND, -1);
            thenCase.addNormalizedChild(cloneEGraph(condition));
            thenCase.addNormalizedChild(thenBranch);

            EGraphNode elseCase = syntheticNode(node, Opcode.AND, -2);
            elseCase.addNormalizedChild(syntheticUnary(node, Opcode.NOT, condition, -3));
            elseCase.addNormalizedChild(elseBranch);

            EGraphNode disjunction = syntheticNode(node, Opcode.OR, -4);
            disjunction.addNormalizedChild(thenCase);
            disjunction.addNormalizedChild(elseCase);
            return disjunction;
        }
        EGraphNode rewritten = copyShallow(node, node.getOpcode());
        for (EGraphNode child : node.getChildren()) {
            EGraphNode rewrittenChild = rewriteBranchConnectives(child);
            if (rewrittenChild != null) {
                rewritten.addNormalizedChild(rewrittenChild);
            }
        }
        return rewritten;
    }

    @LeanVerifiedRewrite("R0-CORE-003")
    private static EGraphNode betaRewriteLet(EGraphNode node, Map<String, EGraphNode> bindings) {
        if (node == null) {
            return null;
        }
        if (node.getOpcode() == Opcode.VARIABLE) {
            EGraphNode replacement = bindings.get(bindingKey(node));
            return replacement == null ? node : cloneEGraph(replacement);
        }
        if (node.getOpcode() == Opcode.LET && node.getChildren().isEmpty()) {
            EGraphNode replacement = bindings.get(bindingKey(node));
            return replacement == null ? node : cloneEGraph(replacement);
        }
        if (node.getOpcode() == Opcode.LET && node.getChildren().size() >= 2) {
            EGraphNode bound = betaRewriteLet(node.getChildren().get(0), bindings);
            Map<String, EGraphNode> scopedBindings = new HashMap<>(bindings);
            String key = bindingKey(node);
            if (key != null) {
                scopedBindings.put(key, bound);
            }
            return betaRewriteLet(node.getChildren().get(1), scopedBindings);
        }
        if (isQuantifierNode(node)) {
            Map<String, EGraphNode> scopedBindings = new HashMap<>(bindings);
            for (EGraphNode child : node.getChildren()) {
                if (isRelDecl(child.getOpcode())) {
                    removeDeclaredVariables(child, scopedBindings);
                }
            }
            EGraphNode rewritten = copyShallow(node, node.getOpcode());
            for (EGraphNode child : node.getChildren()) {
                EGraphNode rewrittenChild = betaRewriteLet(child, isRelDecl(child.getOpcode()) ? bindings : scopedBindings);
                if (rewrittenChild != null) {
                    rewritten.addNormalizedChild(rewrittenChild);
                }
            }
            return rewritten;
        }

        Map<String, EGraphNode> scopedBindings = shadowsLetBindings(node) ? new HashMap<>(bindings) : bindings;
        if (shadowsLetBindings(node)) {
            for (EGraphNode child : node.getChildren()) {
                if (isRelDecl(child.getOpcode())) {
                    removeDeclaredVariables(child, scopedBindings);
                }
            }
        }

        EGraphNode rewritten = copyShallow(node, node.getOpcode());
        for (EGraphNode child : node.getChildren()) {
            EGraphNode rewrittenChild = betaRewriteLet(child, scopedBindings);
            if (rewrittenChild != null) {
                rewritten.addNormalizedChild(rewrittenChild);
            }
        }
        return rewritten;
    }

    private static boolean shadowsLetBindings(EGraphNode node) {
        return isQuantifierNode(node) || node.getOpcode() == Opcode.PREDICATE
                || node.getOpcode() == Opcode.FUNCTION || node.getOpcode() == Opcode.TEMPORALROOT;
    }

    private static void removeDeclaredVariables(EGraphNode relDecl, Map<String, EGraphNode> bindings) {
        List<EGraphNode> children = relDecl.getChildren();
        for (int i = 1; i < children.size(); i++) {
            EGraphNode candidate = children.get(i);
            if (candidate.getOpcode() == Opcode.VARIABLE && bindingKey(candidate) != null) {
                bindings.remove(bindingKey(candidate));
            }
        }
    }

    private EGraphNode prenex(
            EGraphNode node,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            boolean negated,
            List<EGraphNode> constraints,
            String bindingPath,
            boolean canLiftSome,
            boolean canLiftAll,
            boolean globalLift,
            PrenexSlotAllocator slots) {
        return prenexWithBoundaryAllowance(
                node, env, nextVarId, negated, constraints, bindingPath,
                canLiftSome, canLiftAll, globalLift, slots, null);
    }

    @LeanVerifiedRewrite({
            "R0-CORE-011", "R0-CORE-012", "R0-CORE-013", "R0-CORE-015",
            "R0-CORE-016"
    })
    private EGraphNode prenexWithBoundaryAllowance(
            EGraphNode node,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            boolean negated,
            List<EGraphNode> constraints,
            String bindingPath,
            boolean canLiftSome,
            boolean canLiftAll,
            boolean globalLift,
            PrenexSlotAllocator slots,
            Quantifier boundaryAllowance) {
        if (node == null) {
            return null;
        }
        boolean booleanIte = node.getOpcode() == Opcode.ITE;
        if ((isBooleanBranchConnective(node.getOpcode()) || booleanIte)
                && (!EGraphNode.hasBooleanRewriteAuthority(node)
                        || !EGraphNode.hasBooleanOperands(node))) {
            slots.enterStructure(null);
            List<EGraphNode> retainedChildren = new ArrayList<>();
            for (int i = 0; i < node.getChildren().size(); i++) {
                EGraphNode retainedChild = prenex(
                        node.getChildren().get(i), env, nextVarId, false,
                        constraints, bindingPath + "/opaque[" + i + "]",
                        false, false, false, slots);
                if (retainedChild != null && retainedChild.getOpcode() != Opcode.END) {
                    retainedChildren.add(retainedChild);
                }
            }
            node.setNormalizedChildren(retainedChildren);
            return negated ? syntheticUnary(node, Opcode.NOT, node, -1) : node;
        }
        if (node.getOpcode() == Opcode.VARIABLE) {
            QuantiVar qv = env.get(bindingKey(node));
            if (qv != null) {
                node.setAlphaName(qv.getName());
                inheritBindingTypeEvidence(node, qv);
                if (isTemporalSnapshotBinding(qv)) {
                    node.markTemporalSnapshotBinding();
                }
            }
            return node;
        }
        if (node.getOpcode() == Opcode.IFF
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 2) {
            return prenex(expandIff(node, negated), env, nextVarId, false, constraints, bindingPath + "/iff",
                    canLiftSome, canLiftAll, globalLift, slots);
        }
        if (node.getOpcode() == Opcode.NOT
                && EGraphNode.hasBooleanOperands(node)) {
            List<EGraphNode> rewritten = new ArrayList<>();
            for (EGraphNode child : node.getChildren()) {
                EGraphNode rewrittenChild = prenex(child, env, nextVarId, !negated, constraints, bindingPath + "/not",
                        canLiftSome, canLiftAll, globalLift, slots);
                if (rewrittenChild != null && rewrittenChild.getOpcode() != Opcode.END) {
                    rewritten.add(rewrittenChild);
                }
            }
            if (rewritten.isEmpty()) {
                return null;
            }
            return rewritten.size() == 1 ? rewritten.get(0) : conjoin(null, rewritten);
        }
        if (isQuantifierNode(node)) {
            if (node.getOpcode() == Opcode.COMPREHENSION || node.getOpcode() == Opcode.SUM) {
                slots.enterStructure(null);
                return localQuantifier(node, env, nextVarId, negated, bindingPath, slots);
            }
            if (!negated) {
                EGraphNode eliminated = eliminateSingleMembershipQuantifier(node);
                if (eliminated != null) {
                    prenexReductionsThisPass++;
                    return prenex(
                            eliminated,
                            env,
                            nextVarId,
                            false,
                            constraints,
                            bindingPath + "/membership-elimination",
                            canLiftSome,
                            canLiftAll,
                            globalLift,
                            slots);
                }
            }
            Quantifier quantifier = quantifierOf(node.getOpcode(), negated);
            boolean directlyLiftable = canLiftQuantifier(
                    quantifier, canLiftSome, canLiftAll, globalLift)
                    || globalLift && quantifier == boundaryAllowance;
            boolean connectiveAdmitsWitnessLift = canLiftSome || canLiftAll;
            if (!directlyLiftable
                    && (!connectiveAdmitsWitnessLift
                            || !hasScopedInhabitedCarrierWitness(
                                    node, env, quantifier, globalLift))) {
                slots.enterStructure(null);
                return localQuantifier(node, env, nextVarId, negated, bindingPath, slots);
            }
            Boolean preflightEmptyDomain = emptyTupleDomainValue(node, quantifier);
            if (preflightEmptyDomain != null) {
                prenexReductionsThisPass++;
                return booleanConstant(node, preflightEmptyDomain);
            }
            prenexReductionsThisPass++;
            slots.enterQuantifier(quantifier);
            Map<String, QuantiVar> scopedEnv = new HashMap<>(env);
            List<EGraphNode> localConstraints = new ArrayList<>();
            List<EGraphNode> bodyParts = new ArrayList<>();
            List<EGraphNode> children = node.getChildren();
            boolean bodyNegated = (node.getOpcode() == Opcode.NO && !negated)
                    || (negated && !consumesMatrixNegation(node.getOpcode()));
            for (int i = 0; i < children.size(); i++) {
                EGraphNode child = children.get(i);
                if (isRelDecl(child.getOpcode())) {
                    RelDeclResult relDecl = prenexRelDecl(node.getOpcode(), child, scopedEnv, nextVarId, false, negated, localConstraints,
                            bindingPath + "/decl[" + i + "]", slots);
                    if (relDecl.emptyDomainValue != null) {
                        throw new IllegalStateException(
                                "Quantifier-domain preflight disagreed with transformed domain");
                    }
                }
            }
            List<Integer> bodyIndices = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                if (!isRelDecl(children.get(i).getOpcode())) {
                    bodyIndices.add(i);
                }
            }
            boolean bodyCanLiftSome = canLiftSome;
            boolean bodyCanLiftAll = canLiftAll;
            if (!localConstraints.isEmpty()) {
                if (quantifier == Quantifier.ALL) {
                    // The declaration guard will become an implication. An
                    // existential may cross it only when its own carrier is
                    // already known to be inhabited.
                    bodyCanLiftSome = false;
                } else {
                    // Other declaration guards are conjunctive; the dual
                    // universal movement has the same empty-carrier hazard.
                    bodyCanLiftAll = false;
                }
            }
            if (bodyIndices.size() == 1) {
                int index = bodyIndices.get(0);
                EGraphNode rewrittenChild = prenex(
                        children.get(index), scopedEnv, nextVarId, bodyNegated, constraints,
                        bindingPath + "/body[" + index + "]",
                        bodyCanLiftSome, bodyCanLiftAll, globalLift, slots);
                if (rewrittenChild != null) {
                    bodyParts.add(rewrittenChild);
                }
            } else if (!bodyIndices.isEmpty()) {
                slots.enterStructure(Quantifier.ALL);
                PrenexSlotAllocator bodyBase = slots.copy();
                for (int index : bodyIndices) {
                    EGraphNode child = children.get(index);
                    PrenexSlotAllocator childSlots = slots.branchFrom(
                            bodyBase, Quantifier.ALL);
                    EGraphNode rewrittenChild = prenex(
                            child, scopedEnv, nextVarId, bodyNegated, constraints,
                            bindingPath + "/body[" + index + "]",
                            bodyCanLiftSome, bodyCanLiftAll, globalLift, childSlots);
                    slots.mergeBranch(childSlots, Quantifier.ALL);
                    if (rewrittenChild != null) {
                        bodyParts.add(rewrittenChild);
                    }
                }
            }
            EGraphNode body = conjoin(null, bodyParts);
            body = applyDomainConstraints(body, localConstraints, quantifier);
            return body == null ? booleanConstant(node, true) : body;
        }
        if (negated) {
            return prenexNegatedNonQuantifier(node, env, nextVarId, constraints, bindingPath,
                    canLiftSome, canLiftAll, globalLift, slots);
        }

        List<EGraphNode> children = node.getChildren();
        List<EGraphNode> rewritten = new ArrayList<>();
        Quantifier reusable = reusableAcrossBranches(node.getOpcode());
        Quantifier completeBoundary = completeBoundaryQuantifier(node);
        slots.enterStructure(reusable);
        PrenexSlotAllocator branchBase = reusable == null ? null : slots.copy();
        Map<Integer, EGraphNode> rewrittenByIndex = new HashMap<>();
        for (int i : prenexChildOrder(node.getOpcode(), children)) {
            EGraphNode child = children.get(i);
            if (isRelDecl(child.getOpcode())) {
                prenexRelDecl(Opcode.FORALL, child, env, nextVarId, true, false, constraints,
                        bindingPath + "/param[" + i + "]", slots);
                continue;
            }
            boolean childNegated = childNegated(node.getOpcode(), i, negated);
            boolean childCanLiftSome = childCanLiftSome(node.getOpcode(), canLiftSome);
            boolean childCanLiftAll = childCanLiftAll(node.getOpcode(), canLiftAll);
            PrenexSlotAllocator childSlots = reusable == null
                    ? slots
                    : slots.branchFrom(branchBase, reusable);
            EGraphNode rewrittenChild = prenexWithBoundaryAllowance(
                    child, env, nextVarId, childNegated, constraints,
                    childBindingPath(bindingPath, node.getOpcode(), i, childNegated),
                    childCanLiftSome, childCanLiftAll, globalLift, childSlots,
                    completeBoundary);
            if (reusable != null) {
                slots.mergeBranch(childSlots, reusable);
            }
            if (rewrittenChild != null && rewrittenChild.getOpcode() != Opcode.END) {
                rewrittenByIndex.put(i, rewrittenChild);
            }
        }
        for (int i = 0; i < children.size(); i++) {
            EGraphNode rewrittenChild = rewrittenByIndex.get(i);
            if (rewrittenChild != null) {
                rewritten.add(rewrittenChild);
            }
        }
        node.setNormalizedChildren(rewritten);
        return node;
    }

    @LeanVerifiedRewrite("R0-BIND-002")
    private static EGraphNode eliminateSingleMembershipQuantifier(
            EGraphNode quantifier) {
        Opcode quantifierOpcode = quantifier.getOpcode();
        if (quantifierOpcode != Opcode.FORALL
                && quantifierOpcode != Opcode.EXISTS
                && quantifierOpcode != Opcode.NO) {
            return null;
        }
        EGraphNode declaration = null;
        EGraphNode body = null;
        for (EGraphNode child : quantifier.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                if (declaration != null) {
                    return null;
                }
                declaration = child;
            } else {
                if (body != null) {
                    return null;
                }
                body = child;
            }
        }
        if (declaration == null || body == null
                || isDisj(declaration.getOpcode())
                || declaration.getChildren().size() != 2
                || declaration.getChildren().get(1).getOpcode()
                        != Opcode.VARIABLE
                || (body.getOpcode() != Opcode.IN
                        && body.getOpcode() != Opcode.NOT_IN)
                || body.getChildren().size() != 2) {
            return null;
        }
        String expectedComparisonName = body.getOpcode() == Opcode.IN
                ? "BOP_IN" : "BOP_NOT_IN";
        if (!expectedComparisonName.equals(body.getSourceName())
                || !("MIDDLENODE_" + expectedComparisonName).equals(
                        body.getSourceType())) {
            return null;
        }
        EGraphNode binder = declaration.getChildren().get(1);
        EGraphNode compared = body.getChildren().get(0);
        EGraphNode memberSet = body.getChildren().get(1);
        String binderKey = bindingKey(binder);
        if (binderKey == null || compared.getOpcode() != Opcode.VARIABLE
                || !binderKey.equals(bindingKey(compared))
                || containsBindingReference(
                        declaration.getChildren().get(0), binderKey)
                || containsBindingReference(memberSet, binderKey)) {
            return null;
        }
        DomainDescriptor domain = domainDescriptor(
                declaration.getChildren().get(0));
        if (domain.domain == null) {
            return null;
        }
        return EGraphNode.parserCertifiedMembershipQuantifierElimination(
                quantifier,
                quantifierOpcode,
                domain.cardinality,
                compared.getEClassRef(),
                domain.domain.getEClassRef(),
                memberSet.getEClassRef(),
                body.getOpcode() == Opcode.IN);
    }

    private static boolean containsBindingReference(
            EGraphNode node,
            String binding) {
        if (node == null) {
            return false;
        }
        if (node.getOpcode() == Opcode.VARIABLE
                && binding.equals(bindingKey(node))) {
            return true;
        }
        for (EGraphNode child : node.getChildren()) {
            if (containsBindingReference(child, binding)) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> prenexChildOrder(
            Opcode opcode,
            List<EGraphNode> children) {
        List<Integer> order = new ArrayList<>(children.size());
        boolean[] preferred = new boolean[children.size()];
        Quantifier enabling = opcode == Opcode.AND
                ? Quantifier.SOME
                : opcode == Opcode.OR ? Quantifier.ALL : null;
        if (enabling != null) {
            for (int i = 0; i < children.size(); i++) {
                if (reachesBooleanBoundary(children.get(i), opcode, enabling)) {
                    preferred[i] = true;
                    order.add(i);
                }
            }
        }
        for (int i = 0; i < children.size(); i++) {
            if (!preferred[i]) {
                order.add(i);
            }
        }
        return order;
    }

    private static boolean reachesBooleanBoundary(
            EGraphNode node,
            Opcode boundary,
            Quantifier target) {
        if (isQuantifierNode(node)) {
            return quantifierOf(node.getOpcode(), false) == target;
        }
        Opcode opcode = node.getOpcode();
        if (opcode != boundary
                && opcode != Opcode.PREDICATE
                && opcode != Opcode.FUNCTION
                && opcode != Opcode.TEMPORALROOT) {
            return false;
        }
        for (EGraphNode child : node.getChildren()) {
            if (reachesBooleanBoundary(child, boundary, target)) {
                return true;
            }
        }
        return false;
    }

    @LeanVerifiedRewrite("R0-CORE-008")
    private EGraphNode prenexNegatedNonQuantifier(
            EGraphNode node,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            List<EGraphNode> constraints,
            String bindingPath,
            boolean canLiftSome,
            boolean canLiftAll,
            boolean globalLift,
            PrenexSlotAllocator slots) {
        Opcode opcode = node.getOpcode();
        if (opcode == Opcode.AND || opcode == Opcode.OR) {
            Opcode rewrittenOpcode = dualBooleanOpcode(opcode);
            boolean childCanLiftSome = childCanLiftSome(rewrittenOpcode, canLiftSome);
            boolean childCanLiftAll = childCanLiftAll(rewrittenOpcode, canLiftAll);
            EGraphNode rewritten = copyShallow(node, dualBooleanOpcode(opcode));
            Quantifier reusable = reusableAcrossBranches(rewrittenOpcode);
            slots.enterStructure(reusable);
            PrenexSlotAllocator branchBase = slots.copy();
            for (int i = 0; i < node.getChildren().size(); i++) {
                PrenexSlotAllocator childSlots = slots.branchFrom(branchBase, reusable);
                EGraphNode child = prenex(node.getChildren().get(i), env, nextVarId, true, constraints,
                        childBindingPath(bindingPath, opcode, i, true), childCanLiftSome, childCanLiftAll, globalLift, childSlots);
                slots.mergeBranch(childSlots, reusable);
                if (child != null && child.getOpcode() != Opcode.END) {
                    rewritten.addNormalizedChild(child);
                }
            }
            return rewritten;
        }
        if (opcode == Opcode.IMPLIES && node.getChildren().size() == 2) {
            EGraphNode conjunction = syntheticNode(node, Opcode.AND, -1);
            boolean childCanLiftSome = childCanLiftSome(Opcode.AND, canLiftSome);
            boolean childCanLiftAll = childCanLiftAll(Opcode.AND, canLiftAll);
            EGraphNode left = prenex(node.getChildren().get(0), env, nextVarId, false, constraints,
                    bindingPath + "/implies[0]", childCanLiftSome, childCanLiftAll, globalLift, slots);
            EGraphNode right = prenex(node.getChildren().get(1), env, nextVarId, true, constraints,
                    bindingPath + "/implies[1]/not", childCanLiftSome, childCanLiftAll, globalLift, slots);
            if (left != null && left.getOpcode() != Opcode.END) {
                conjunction.addNormalizedChild(left);
            }
            if (right != null && right.getOpcode() != Opcode.END) {
                conjunction.addNormalizedChild(right);
            }
            return conjunction;
        }
        if (opcode == Opcode.ITE
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 3) {
            return prenex(expandIte(node, true), env, nextVarId, false, constraints, bindingPath + "/ite",
                    canLiftSome, canLiftAll, globalLift, slots);
        }
        Opcode dual = dualOpcode(opcode);
        if (dual != null) {
            EGraphNode rewritten = copyShallow(node, dual);
            boolean negateChildren = dualNegatesChildren(opcode);
            for (int i = 0; i < node.getChildren().size(); i++) {
                EGraphNode child = prenex(node.getChildren().get(i), env, nextVarId, negateChildren, constraints,
                        childBindingPath(bindingPath, opcode, i, negateChildren), canLiftSome, canLiftAll, globalLift, slots);
                if (child != null && child.getOpcode() != Opcode.END) {
                    rewritten.addNormalizedChild(child);
                }
            }
            return rewritten;
        }
        EGraphNode positive = prenex(node, env, nextVarId, false, constraints, bindingPath + "/positive",
                canLiftSome, canLiftAll, globalLift, slots);
        return positive == null ? null : syntheticUnary(node, Opcode.NOT, positive, -1);
    }

    private static boolean canLiftQuantifier(
            Quantifier quantifier,
            boolean canLiftSome,
            boolean canLiftAll,
            boolean globalLift) {
        if (!globalLift) {
            return false;
        }
        switch (quantifier) {
            case ALL:
            case NO:
                return canLiftAll;
            case SOME:
                return canLiftSome;
            default:
                return canLiftSome && canLiftAll;
        }
    }

    private static Quantifier reusableAcrossBranches(Opcode opcode) {
        switch (opcode) {
            case AND:
            case PREDICATE:
            case FUNCTION:
            case TEMPORALROOT:
                return Quantifier.ALL;
            case OR:
                return Quantifier.SOME;
            default:
                return null;
        }
    }

    private static Quantifier completeBoundaryQuantifier(EGraphNode node) {
        Quantifier target;
        if (node.getOpcode() == Opcode.AND) {
            target = Quantifier.ALL;
        } else if (node.getOpcode() == Opcode.OR) {
            target = Quantifier.SOME;
        } else {
            return null;
        }
        java.util.Set<String> commonFactors = null;
        boolean sawBranch = false;
        for (EGraphNode branch : node.getChildren()) {
            if (branch.getOpcode() == Opcode.END) {
                continue;
            }
            sawBranch = true;
            if (!isQuantifierNode(branch)
                    || quantifierOf(branch.getOpcode(), false) != target) {
                return null;
            }
            java.util.Set<String> factors = new java.util.LinkedHashSet<>();
            for (EGraphNode declaration : branch.getChildren()) {
                if (!isRelDecl(declaration.getOpcode())) {
                    continue;
                }
                if (isDisj(declaration.getOpcode())
                        || declaration.getChildren().isEmpty()) {
                    return null;
                }
                factors.add(sortKey(declaration.getChildren().get(0)));
            }
            if (factors.isEmpty()) {
                return null;
            }
            if (commonFactors == null) {
                commonFactors = factors;
            } else if (!commonFactors.equals(factors)) {
                return null;
            }
        }
        return sawBranch ? target : null;
    }

    private static boolean childCanLiftSome(Opcode opcode, boolean current) {
        switch (opcode) {
            case AND:
            case PREDICATE:
            case FUNCTION:
            case TEMPORALROOT:
                return current;
            default:
                return false;
        }
    }

    private static boolean childCanLiftAll(Opcode opcode, boolean current) {
        switch (opcode) {
            case OR:
            case PREDICATE:
            case FUNCTION:
            case TEMPORALROOT:
                return current;
            default:
                return false;
        }
    }

    private EGraphNode localQuantifier(
            EGraphNode node,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            boolean negated,
            String bindingPath,
            PrenexSlotAllocator slots) {
        Boolean emptyDomain = emptyTupleDomainValue(
                node, quantifierOf(node.getOpcode(), negated));
        if (emptyDomain != null) {
            prenexReductionsThisPass++;
            return booleanConstant(node, emptyDomain);
        }
        EGraphNode quantifier = copyShallow(node, node.getOpcode());
        Map<String, QuantiVar> scopedEnv = new HashMap<>(env);
        List<EGraphNode> bodyParts = new ArrayList<>();
        List<EGraphNode> localConstraints = new ArrayList<>();
        int localDepth = localBinderDepth(scopedEnv);
        int[] localOrdinal = {0};
        List<EGraphNode> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) {
            EGraphNode child = children.get(i);
            if (isRelDecl(child.getOpcode())) {
                quantifier.addNormalizedChild(localRelDecl(node.getOpcode(), child, scopedEnv, nextVarId, negated,
                        localConstraints, bindingPath + "/local-decl[" + i + "]", slots,
                        localDepth, localOrdinal));
            } else {
                EGraphNode rewrittenChild = prenex(child, scopedEnv, nextVarId, false, localConstraints,
                        bindingPath + "/local-body[" + i + "]", true, true, false, slots);
                if (rewrittenChild != null && rewrittenChild.getOpcode() != Opcode.END) {
                    bodyParts.add(rewrittenChild);
                }
            }
        }
        EGraphNode body = conjoin(null, bodyParts);
        body = applyDomainConstraints(body, localConstraints, quantifierOf(node.getOpcode(), false));
        if (body != null) {
            quantifier.addNormalizedChild(body);
        }
        return negated ? syntheticUnary(node, Opcode.NOT, quantifier, -1) : quantifier;
    }

    private EGraphNode localRelDecl(
            Opcode quantifierOpcode,
            EGraphNode relDecl,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            boolean negated,
            List<EGraphNode> constraints,
            String bindingPath,
            PrenexSlotAllocator slots,
            int localDepth,
            int[] localOrdinal) {
        EGraphNode copy = copyShallow(relDecl, relDecl.getOpcode());
        EGraphNode typeEGraph = null;
        if (!relDecl.getChildren().isEmpty()) {
            PrenexSlotAllocator domainSlots = slots.domainView();
            typeEGraph = prenex(relDecl.getChildren().get(0), env, nextVarId, false, constraints,
                    bindingPath + "/type", true, true, false, domainSlots);
            copy.addNormalizedChild(typeEGraph == null ? relDecl.getChildren().get(0) : typeEGraph);
        }
        EGraphNode normalizedTypeEGraph = typeEGraph == null
                ? null : toNNF(typeEGraph, false);
        DomainDescriptor domain = domainDescriptor(normalizedTypeEGraph);
        boolean disj = isDisj(relDecl.getOpcode());
        int disjointnessClass = disj ? nextDisjointnessClass++ : 0;
        List<EGraphNode> children = relDecl.getChildren();
        for (int i = 1; i < children.size(); i++) {
            EGraphNode candidate = children.get(i);
            EGraphNode candidateCopy = cloneEGraph(candidate);
            if (candidate.getOpcode() == Opcode.VARIABLE) {
                String originalName = candidate.getSourceName();
                int ordinal = localOrdinal[0]++;
                int localId = nextVarId[0]++;
                String alphaName = "_l" + localId;
                String varType = primitiveVarType(candidate.getSourceType());
                QuantiVar qv = new QuantiVar(localId, alphaName, originalName, varType);
                qv.addOriginalName(candidate.getSemanticIdentity());
                qv.mergeExactAlloyType(bindingTypeEvidence(candidate, typeEGraph));
                qv.setQuantifier(quantifierOf(quantifierOpcode, negated));
                qv.setCardinality(domain.cardinality);
                qv.setDisjointnessClass(disjointnessClass);
                qv.setBindingPath(bindingPath);
                qv.setDeBruijnKey("local#" + localDepth + "#" + ordinal
                        + ":" + qv.getCardinality()
                        + ":" + normalizeType(varType)
                        + "@" + bindingPath);
                candidateCopy.setAlphaName(alphaName);
                LocalBindingSeed prior = localBindingSeeds.put(
                        alphaName,
                        new LocalBindingSeed(
                                qv, candidate.getSourceOccurrenceLineage()));
                if (prior != null && prior.variable != qv) {
                    throw new IllegalStateException(
                            "A local alpha identity was issued twice");
                }
                if (needsDomainConstraint(domain, varType)) {
                    constraints.add(domainConstraint(qv, candidateCopy, domain.domain));
                }
                String key = bindingKey(candidate);
                if (key != null) {
                    env.put(key, qv);
                }
            }
            copy.addNormalizedChild(candidateCopy);
        }
        return copy;
    }

    private static int localBinderDepth(Map<String, QuantiVar> env) {
        int depth = 0;
        for (QuantiVar variable : new java.util.LinkedHashSet<>(env.values())) {
            String key = variable.getDeBruijnKey();
            if (key == null || !key.startsWith("local#")) {
                continue;
            }
            int separator = key.indexOf('#', "local#".length());
            if (separator < 0) {
                throw new IllegalStateException("Malformed local De Bruijn key: " + key);
            }
            try {
                depth = Math.max(
                        depth,
                        Integer.parseInt(key.substring("local#".length(), separator)) + 1);
            } catch (NumberFormatException exception) {
                throw new IllegalStateException(
                        "Malformed local De Bruijn depth: " + key, exception);
            }
        }
        return depth;
    }

    private void captureTemporalPhaseLocalBindings() {
        Map<String, ScopedLocalBinding> visible = new java.util.LinkedHashMap<>();
        for (PhaseLocalBindingImport imported : phaseLocalBindingImports) {
            putScopedBinding(visible, ScopedLocalBinding.from(imported));
        }
        captureTemporalPhaseLocalBindings(certificationMatrixEGraphRoot, visible);
    }

    private void captureTemporalPhaseLocalBindings(
            EGraphNode node,
            Map<String, ScopedLocalBinding> visible) {
        if (node == null) {
            return;
        }
        int[] target = temporalReferenceTarget(node);
        if (target != null) {
            java.util.Set<ScopedLocalBinding> unique = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            unique.addAll(visible.values());
            for (int offset = 0; offset < target[1]; offset++) {
                NormalForm child = temporalChildren.get(target[0] + offset);
                List<PhaseLocalBindingImport> imports = new ArrayList<>(unique.size());
                for (ScopedLocalBinding binding : unique) {
                    imports.add(binding.importInto(child.phasePath));
                }
                imports.sort(java.util.Comparator
                        .comparing((PhaseLocalBindingImport value) -> value.binderContext())
                        .thenComparingInt(value -> value.variable().getId()));
                List<PhaseLocalBindingImport> snapshot =
                        Collections.unmodifiableList(imports);
                List<PhaseLocalBindingImport> prior = temporalChildBindingImports.get(child);
                if (prior == null) {
                    temporalChildBindingImports.put(child, snapshot);
                } else if (!samePhaseLocalBindingSnapshot(prior, snapshot)) {
                    throw new IllegalStateException(
                            "A temporal child was referenced under conflicting local scopes");
                }
            }
            return;
        }
        if (isQuantifierNode(node)) {
            Map<String, ScopedLocalBinding> scoped = new java.util.LinkedHashMap<>(visible);
            for (EGraphNode child : node.getChildren()) {
                if (!isRelDecl(child.getOpcode())) {
                    continue;
                }
                if (!child.getChildren().isEmpty()) {
                    captureTemporalPhaseLocalBindings(child.getChildren().get(0), scoped);
                }
                for (int index = 1; index < child.getChildren().size(); index++) {
                    EGraphNode variableNode = child.getChildren().get(index);
                    if (variableNode.getOpcode() != Opcode.VARIABLE) {
                        continue;
                    }
                    String alpha = variableNode.getAlphaName() != null
                                    && !variableNode.getAlphaName().isEmpty()
                            ? variableNode.getAlphaName()
                            : variableNode.getSourceName();
                    LocalBindingSeed seed = localBindingSeeds.get(alpha);
                    if (seed == null) {
                        throw new IllegalStateException(
                                "A retained local binder lacks its normalized binding seed: "
                                        + alpha);
                    }
                    putScopedBinding(
                            scoped,
                            new ScopedLocalBinding(
                                    seed.variable,
                                    this,
                                    node,
                                    seed.sourceBinderLineage,
                                    phasePath,
                                    seed.variable.getBindingPath()));
                }
            }
            for (EGraphNode child : node.getChildren()) {
                if (!isRelDecl(child.getOpcode())) {
                    captureTemporalPhaseLocalBindings(child, scoped);
                }
            }
            return;
        }
        for (EGraphNode child : node.getChildren()) {
            captureTemporalPhaseLocalBindings(child, visible);
        }
    }

    private static boolean samePhaseLocalBindingSnapshot(
            List<PhaseLocalBindingImport> left,
            List<PhaseLocalBindingImport> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            PhaseLocalBindingImport a = left.get(index);
            PhaseLocalBindingImport b = right.get(index);
            if (a.variable() != b.variable()
                    || a.ownerPhase() != b.ownerPhase()
                    || a.ownerBinder() != b.ownerBinder()
                    || a.sourceBinderLineage() != b.sourceBinderLineage()
                    || !a.ownerPhasePath().equals(b.ownerPhasePath())
                    || !a.targetPhasePath().equals(b.targetPhasePath())
                    || !a.binderContext().equals(b.binderContext())) {
                return false;
            }
        }
        return true;
    }

    private static void putScopedBinding(
            Map<String, ScopedLocalBinding> scope,
            ScopedLocalBinding binding) {
        java.util.LinkedHashSet<String> aliases = new java.util.LinkedHashSet<>();
        aliases.add(binding.variable.getName());
        aliases.add(binding.variable.getDeBruijnKey());
        aliases.addAll(binding.variable.getOriginalNames());
        java.util.Set<ScopedLocalBinding> shadowed = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (String alias : aliases) {
            ScopedLocalBinding prior = scope.get(alias);
            if (prior != null && prior != binding) {
                shadowed.add(prior);
            }
        }
        if (!shadowed.isEmpty()) {
            scope.entrySet().removeIf(entry -> shadowed.contains(entry.getValue()));
        }
        for (String alias : aliases) {
            if (alias != null && !alias.isEmpty()) {
                scope.put(alias, binding);
            }
        }
    }

    private boolean hasScopedInhabitedCarrierWitness(
            EGraphNode quantifierNode,
            Map<String, QuantiVar> env,
            Quantifier quantifier,
            boolean globalLift) {
        if (!globalLift
                || quantifier != Quantifier.ALL
                        && quantifier != Quantifier.NO
                        && quantifier != Quantifier.SOME) {
            return false;
        }
        boolean foundDeclaration = false;
        for (EGraphNode child : quantifierNode.getChildren()) {
            if (!isRelDecl(child.getOpcode())) {
                continue;
            }
            foundDeclaration = true;
            if (isDisj(child.getOpcode()) || child.getChildren().isEmpty()) {
                return false;
            }
            DomainDescriptor domain = domainDescriptor(child.getChildren().get(0));
            if (hasEmptyAdmissibleBindingSet(domain)) {
                return false;
            }
            for (int index = 1; index < child.getChildren().size(); index++) {
                EGraphNode variable = child.getChildren().get(index);
                if (variable.getOpcode() != Opcode.VARIABLE) {
                    continue;
                }
                String primitiveType = primitiveVarType(variable.getSourceType());
                if (!domainUsesPrimitiveCarrier(domain.domain, primitiveType)
                        || !hasNonemptyBindingOfType(env, primitiveType)) {
                    return false;
                }
            }
        }
        return foundDeclaration;
    }

    private boolean hasNonemptyBindingOfType(
            Map<String, QuantiVar> env,
            String primitiveType) {
        java.util.LinkedHashSet<QuantiVar> enclosing =
                new java.util.LinkedHashSet<>(env.values());
        enclosing.addAll(matrixQuantiVars);
        String required = normalizeType(primitiveType);
        for (QuantiVar binding : enclosing) {
            if (normalizeType(binding.getTypeName()).equals(required)
                    && normalizeType(binding.getCarrierTypeName()).equals(required)
                    && cardinalityGuaranteesNonempty(binding.getCardinality())) {
                return true;
            }
        }
        return false;
    }

    private boolean isTemporalSnapshotBinding(QuantiVar variable) {
        for (QuantiVar inherited : inheritedQuantiVars) {
            if (inherited == variable) {
                return true;
            }
        }
        for (PhaseLocalBindingImport imported : phaseLocalBindingImports) {
            if (imported.variable() == variable) {
                return true;
            }
        }
        return false;
    }

    @LeanVerifiedRewrite("R0-BIND-001")
    private RelDeclResult prenexRelDecl(
            Opcode quantifierOpcode,
            EGraphNode relDecl,
            Map<String, QuantiVar> env,
            int[] nextVarId,
            boolean parameterDecl,
            boolean negated,
            List<EGraphNode> constraints,
            String bindingPath,
            PrenexSlotAllocator slots) {
        EGraphNode typeEGraph = null;
        if (!relDecl.getChildren().isEmpty()) {
            PrenexSlotAllocator domainSlots = slots.domainView();
            typeEGraph = prenex(relDecl.getChildren().get(0), env, nextVarId, false, constraints,
                    bindingPath + "/type", true, true, false, domainSlots);
        }
        EGraphNode normalizedTypeEGraph = typeEGraph == null
                ? null : toNNF(typeEGraph, false);
        DomainDescriptor domain = domainDescriptor(normalizedTypeEGraph);
        List<QuantiVar> quantiVars = new ArrayList<>();
        Quantifier quantifier = quantifierOf(quantifierOpcode, negated);
        if (hasEmptyAdmissibleBindingSet(domain)) {
            return new RelDeclResult(quantiVars, emptyDomainValue(quantifier));
        }
        boolean disj = isDisj(relDecl.getOpcode());
        int disjointnessClass = disj ? nextDisjointnessClass++ : 0;
        String deBruijnBase = bindingPath + (negated ? "@neg" : "@pos");

        List<EGraphNode> children = relDecl.getChildren();
        for (int i = 1; i < children.size(); i++) {
            EGraphNode candidate = children.get(i);
            if (candidate.getOpcode() != Opcode.VARIABLE) {
                continue;
            }
            String key = bindingKey(candidate);
            String originalName = candidate.getSourceName();
            String alphaName = "_q" + nextVarId[0];
            String varType = primitiveVarType(candidate.getSourceType());
            QuantiVar qv = new QuantiVar(nextVarId[0], alphaName, originalName, varType);
            qv.addOriginalName(candidate.getSemanticIdentity());
            qv.mergeExactAlloyType(bindingTypeEvidence(candidate, typeEGraph));
            qv.setQuantifier(quantifier);
            qv.setCardinality(domain.cardinality);
            boolean guardedDomain = domain.domain != null
                    && needsDomainConstraint(domain, varType);
            qv.setDisjointnessClass(disjointnessClass);
            qv.setBindingPath(deBruijnBase);
            qv.setDeBruijnKey(deBruijnBase + "#" + (i - 1) + ":" + qv.getCardinality()
                    + ":" + normalizeType(varType));
            QuantiVar representative = parameterDecl ? qv : slots.bind(qv);
            if (representative == qv) {
                nextVarId[0]++;
            }
            candidate.setAlphaName(representative.getName());
            quantiVars.add(representative);
            if (guardedDomain) {
                constraints.add(domainConstraint(representative, candidate, domain.domain));
            }
            if (parameterDecl) {
                params.add(representative);
            }
            if (key != null) {
                env.put(key, representative);
            }
        }
        return new RelDeclResult(quantiVars, null);
    }

    private static Boolean emptyDomainValue(Quantifier quantifier) {
        switch (quantifier) {
            case ALL:
            case NO:
            case LONE:
            case NOTONE:
                return true;
            case SOME:
            case ONE:
            case NOTLONE:
                return false;
            default:
                return null;
        }
    }

    @LeanVerifiedRewrite("R0-CORE-013")
    private static Boolean emptyTupleDomainValue(
            EGraphNode quantifierNode,
            Quantifier quantifier) {
        if (quantifier == Quantifier.SUM || quantifier == Quantifier.COMPREHENSION) {
            return null;
        }
        for (EGraphNode child : quantifierNode.getChildren()) {
            if (!isRelDecl(child.getOpcode()) || child.getChildren().isEmpty()) {
                continue;
            }
            EGraphNode normalizedDomain = removeEndNodes(
                    toNNF(cloneEGraph(child.getChildren().get(0)), false));
            if (hasEmptyAdmissibleBindingSet(domainDescriptor(normalizedDomain))) {
                return emptyDomainValue(quantifier);
            }
        }
        return null;
    }

    @LeanVerifiedRewrite("R0-BIND-001")
    private static EGraphNode applyDomainConstraints(EGraphNode body, List<EGraphNode> constraints, Quantifier quantifier) {
        if (constraints.isEmpty()) {
            return body;
        }
        EGraphNode domain = conjoin(null, constraints);
        if (quantifier == Quantifier.ALL) {
            if (body == null) {
                return domain;
            }
            EGraphNode implication = syntheticNode(domain, Opcode.IMPLIES, -1);
            implication.addNormalizedChild(domain);
            implication.addNormalizedChild(body);
            return implication;
        }
        return conjoin(body, constraints);
    }

    private static EGraphNode conjoin(EGraphNode body, List<EGraphNode> constraints) {
        if (constraints.isEmpty()) {
            return body;
        }
        if (body == null && constraints.size() == 1) {
            return constraints.get(0);
        }
        EGraphNode source = body == null ? constraints.get(0) : body;
        EGraphNode conjunction = syntheticNode(source, Opcode.AND, -1);
        if (body != null) {
            conjunction.addNormalizedChild(body);
        }
        for (EGraphNode constraint : constraints) {
            conjunction.addNormalizedChild(constraint);
        }
        return conjunction;
    }

    private static EGraphNode booleanConstant(EGraphNode source, boolean value) {
        EGraphNode constant = syntheticNode(source, Opcode.CONSTANT, value ? -7 : -8);
        constant.setSourceName(Boolean.toString(value));
        constant.setSourceType("Bool");
        constant.setExactAlloyType(
                is.fivefivefive.ACGN.alloy.ExactAlloyType.boolType());
        return constant;
    }

    /**
     * Source-semantic smart rules that must be visible at the certified
     * adapter boundary. Algebraic A/C/I normalization deliberately remains
     * after the certification snapshot and requires its own law evidence.
     */
    private Map<String, QuantiVar> guardedBindingFacts() {
        Map<String, QuantiVar> result = new HashMap<>();
        addGuardedBindingFacts(result, params);
        addGuardedBindingFacts(result, inheritedQuantiVars);
        addGuardedBindingFacts(result, phaseLocalVariables());
        addGuardedBindingFacts(result, matrixQuantiVars);
        return result;
    }

    private List<QuantiVar> phaseLocalVariables() {
        List<QuantiVar> result = new ArrayList<>(phaseLocalBindingImports.size());
        for (PhaseLocalBindingImport imported : phaseLocalBindingImports) {
            result.add(imported.variable());
        }
        return result;
    }

    private static void addGuardedBindingFacts(
            Map<String, QuantiVar> target,
            List<QuantiVar> variables) {
        for (QuantiVar variable : variables) {
            target.put(variable.getName(), variable);
            target.put(variable.getDeBruijnKey(), variable);
            for (String original : variable.getOriginalNames()) {
                target.putIfAbsent(original, variable);
            }
        }
    }

    @LeanVerifiedRewrite({
            "R0-CORE-007", "R0-CORE-020", "R0-CORE-021", "R0-CORE-022",
            "R0-CORE-023", "R0-CORE-024", "R0-REL-004", "R0-REL-034"
    })
    private static EGraphNode normalizeGuardedSourceRules(
            EGraphNode node,
            Map<String, QuantiVar> bindings) {
        if (node == null) {
            return null;
        }
        List<EGraphNode> rewritten = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            EGraphNode normalized = normalizeGuardedSourceRules(child, bindings);
            if (normalized != null && normalized.getOpcode() != Opcode.END) {
                rewritten.add(normalized);
            }
        }
        node.setNormalizedChildren(rewritten);

        if (node.getOpcode() == Opcode.GLOBALBINDING
                && node.getChildren().isEmpty()) {
            EGraphNode abstractCarrier =
                    EGraphNode.parserCertifiedAbstractUnionCarrier(
                            node, Collections.singletonList(node));
            if (abstractCarrier != null) {
                return abstractCarrier;
            }
        }

        EGraphNode relationalRewrite = normalizeRelationalUnaryAndIdentityRules(
                node, rewritten, bindings);
        if (relationalRewrite != node) {
            return relationalRewrite;
        }

        if (node.getOpcode() == Opcode.NOT
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && rewritten.size() == 1) {
            Boolean child = booleanConstantValue(rewritten.get(0));
            if (child != null) {
                return booleanConstant(node, !child);
            }
            EGraphNode rewrittenChild = rewritten.get(0);
            if ((rewrittenChild.getOpcode() == Opcode.AND
                            || rewrittenChild.getOpcode() == Opcode.OR)
                    && EGraphNode.hasBooleanRewriteAuthority(rewrittenChild)
                    && EGraphNode.hasBooleanOperands(rewrittenChild)) {
                return normalizeGuardedSourceRules(
                        removeEndNodes(toNNF(node, false)), bindings);
            }
        }

        if ((node.getOpcode() == Opcode.AND || node.getOpcode() == Opcode.OR)
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)) {
            boolean conjunction = node.getOpcode() == Opcode.AND;
            boolean absorbing = !conjunction;
            boolean neutral = conjunction;
            List<EGraphNode> retained = new ArrayList<>(rewritten.size());
            for (EGraphNode child : rewritten) {
                Boolean value = booleanConstantValue(child);
                if (value != null && value == absorbing) {
                    return booleanConstant(node, absorbing);
                }
                if (value == null || value != neutral) {
                    retained.add(child);
                }
            }
            if (retained.isEmpty()) {
                return booleanConstant(node, neutral);
            }
            if (containsSemanticComplement(retained)) {
                return booleanConstant(node, !conjunction);
            }
            if (retained.size() == 1) {
                return retained.get(0);
            }
            node.setNormalizedChildren(retained);
            if (EGraphNode.containsCertifiedCoveredDualBranch(
                    node, node.getChildClasses())) {
                return booleanConstant(node, !conjunction);
            }
            EGraphNode lattice = EGraphNode.parserCertifiedLatticeNormalForm(
                    node, node.getChildClasses());
            if (lattice != null) {
                return normalizeGuardedSourceRules(lattice, bindings);
            }
        }

        if (node.getOpcode() == Opcode.PLUS) {
            for (EGraphNode child : rewritten) {
                if (isUniv(child)) {
                    return child;
                }
            }
            List<EGraphNode> retained = new ArrayList<>(rewritten.size());
            for (EGraphNode child : rewritten) {
                if (!isNone(child)) {
                    retained.add(child);
                }
            }
            if (retained.size() != rewritten.size()) {
                if (retained.isEmpty()) {
                    return rewritten.get(0);
                }
                if (retained.size() == 1) {
                    return retained.get(0);
                }
                node.setNormalizedChildren(retained);
            }
            EGraphNode partition =
                    EGraphNode.parserCertifiedDifferencePartitionRecombination(
                            node, node.getChildClasses());
            if (partition != null) {
                return normalizeGuardedSourceRules(partition, bindings);
            }
            EGraphNode structuralAbsorption =
                    EGraphNode.parserCertifiedStructuralLatticeAbsorption(
                            node, node.getChildClasses());
            if (structuralAbsorption != null) {
                return normalizeGuardedSourceRules(
                        structuralAbsorption, bindings);
            }
            EGraphNode restriction =
                    EGraphNode.parserCertifiedRestrictionLatticeFactoring(
                            node, node.getChildClasses());
            if (restriction != null) {
                return normalizeGuardedSourceRules(restriction, bindings);
            }
            EGraphNode difference = EGraphNode.parserCertifiedDifferenceFactoring(
                    node, node.getChildClasses());
            if (difference != null) {
                return difference;
            }
            EGraphNode join = EGraphNode.parserCertifiedJoinUnionFactoring(
                    node, node.getChildClasses());
            if (join != null) {
                // Factoring an outer chain coordinate can expose a union of
                // shorter JOINs at the next coordinate. Close that strictly
                // smaller duplicated-chain problem before the certification
                // matrix is cloned; post-snapshot transfer may then compare
                // only the certified ACI operand, never a distributive edit.
                return normalizeGuardedSourceRules(join, bindings);
            }
            EGraphNode productCarrier =
                    EGraphNode.parserCertifiedProductUnionCarrier(
                            node, node.getChildClasses());
            if (productCarrier != null) {
                return productCarrier;
            }
            EGraphNode lattice = EGraphNode.parserCertifiedLatticeNormalForm(
                    node, node.getChildClasses());
            if (lattice != null) {
                return normalizeGuardedSourceRules(lattice, bindings);
            }
            EGraphNode abstractCarrier =
                    EGraphNode.parserCertifiedAbstractUnionCarrier(
                            node, retained);
            if (abstractCarrier != null) {
                return abstractCarrier;
            }
            retained = removeSubrelationsCoveredByFullCarriers(retained);
            if (retained.size() == 1) {
                return retained.get(0);
            }
            if (!retained.equals(node.getChildren())) {
                node.setNormalizedChildren(retained);
            }
        }

        if (node.getOpcode() == Opcode.INTERSECT) {
            for (EGraphNode child : rewritten) {
                if (isNone(child)) {
                    return child;
                }
            }
            List<EGraphNode> retained = new ArrayList<>(rewritten.size());
            for (EGraphNode child : rewritten) {
                if (!isUniv(child)) {
                    retained.add(child);
                }
            }
            if (retained.size() != rewritten.size()) {
                if (retained.isEmpty()) {
                    return rewritten.get(0);
                }
                if (retained.size() == 1) {
                    return retained.get(0);
                }
                node.setNormalizedChildren(retained);
            }
            retained = removeFullCarriersContainingSubrelations(retained);
            if (retained.size() == 1) {
                return retained.get(0);
            }
            if (!retained.equals(node.getChildren())) {
                node.setNormalizedChildren(retained);
            }
            EGraphNode disjoint =
                    EGraphNode.parserCertifiedDifferenceDisjointness(
                            node, node.getChildClasses());
            if (disjoint != null) {
                return disjoint;
            }
            EGraphNode structuralAbsorption =
                    EGraphNode.parserCertifiedStructuralLatticeAbsorption(
                            node, node.getChildClasses());
            if (structuralAbsorption != null) {
                return normalizeGuardedSourceRules(
                        structuralAbsorption, bindings);
            }
            EGraphNode restriction =
                    EGraphNode.parserCertifiedRestrictionLatticeFactoring(
                            node, node.getChildClasses());
            if (restriction != null) {
                return normalizeGuardedSourceRules(restriction, bindings);
            }
            EGraphNode productIntersection =
                    EGraphNode.parserCertifiedProductIntersectionFactoring(
                            node, node.getChildClasses());
            if (productIntersection != null) {
                return normalizeGuardedSourceRules(
                        productIntersection, bindings);
            }
            EGraphNode difference = EGraphNode.parserCertifiedDifferenceFactoring(
                    node, node.getChildClasses());
            if (difference != null) {
                return difference;
            }
            EGraphNode extractedDifference =
                    EGraphNode.parserCertifiedIntersectionDifferenceExtraction(
                            node, node.getChildClasses());
            if (extractedDifference != null) {
                return normalizeGuardedSourceRules(
                        extractedDifference, bindings);
            }
            EGraphNode lattice = EGraphNode.parserCertifiedLatticeNormalForm(
                    node, node.getChildClasses());
            if (lattice != null) {
                return normalizeGuardedSourceRules(lattice, bindings);
            }
        }

        if (node.getOpcode() == Opcode.MINUS && rewritten.size() == 2) {
            EGraphNode left = rewritten.get(0);
            EGraphNode right = rewritten.get(1);
            EGraphNode partition =
                    EGraphNode.parserCertifiedDifferencePartitionNormalization(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (partition != null) {
                return normalizeGuardedSourceRules(partition, bindings);
            }
            if (isNone(left)) {
                return left;
            }
            if (isNone(right)) {
                return left;
            }
            if (isUniv(right)
                    || EGraphNode.sameSemanticInvocation(left, right)) {
                return EGraphNode.derivedSetConstant(
                        node,
                        syntheticId(node, -9),
                        "none",
                        node.getExactAlloyType());
            }
            EGraphNode restriction =
                    EGraphNode.parserCertifiedRestrictionDifferenceFactoring(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (restriction != null) {
                return normalizeGuardedSourceRules(restriction, bindings);
            }
            EGraphNode productDifference =
                    EGraphNode.parserCertifiedProductDifferenceFactoring(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (productDifference != null) {
                return normalizeGuardedSourceRules(
                        productDifference, bindings);
            }
            EGraphNode rightNestedDifference =
                    EGraphNode.parserCertifiedRightNestedDifference(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (rightNestedDifference != null) {
                return normalizeGuardedSourceRules(
                        rightNestedDifference, bindings);
            }
            EGraphNode nestedDifference =
                    EGraphNode.parserCertifiedLeftNestedDifference(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (nestedDifference != null) {
                return normalizeGuardedSourceRules(
                        nestedDifference, bindings);
            }
        }


        if ((node.getOpcode() == Opcode.ARROW || node.getOpcode() == Opcode.JOIN)
                && EGraphNode.isExactRelationNode(node)) {
            for (EGraphNode child : rewritten) {
                if (isNone(child)) {
                    return EGraphNode.derivedSetConstant(
                            node,
                            syntheticId(node, -10),
                            "none",
                            node.getExactAlloyType());
                }
            }
        }

        Boolean reflexiveComparison =
                EGraphNode.parserCertifiedReflexiveComparison(
                        node, node.getChildClasses());
        if (reflexiveComparison != null) {
            return booleanConstant(node, reflexiveComparison);
        }

        Boolean structuralSubset =
                EGraphNode.parserCertifiedStructuralSubsetComparison(
                        node, node.getChildClasses());
        if (structuralSubset != null) {
            return booleanConstant(node, structuralSubset);
        }

        EGraphNode subsetExpansion =
                EGraphNode.parserCertifiedSubsetLatticeExpansion(
                        node, node.getChildClasses());
        if (subsetExpansion != null) {
            return normalizeGuardedSourceRules(subsetExpansion, bindings);
        }

        if ((node.getOpcode() == Opcode.IN
                        || node.getOpcode() == Opcode.NOT_IN)
                && rewritten.size() == 2
                && isNone(rewritten.get(1))
                && isStaticallyNonemptySet(rewritten.get(0), bindings)) {
            return booleanConstant(node, node.getOpcode() == Opcode.NOT_IN);
        }

        EGraphNode emptyRightSubset =
                EGraphNode.parserCertifiedEmptyRightSubsetExpansion(
                        node, node.getChildClasses());
        if (emptyRightSubset != null) {
            return normalizeGuardedSourceRules(emptyRightSubset, bindings);
        }

        if (node.getOpcode() == Opcode.IN && rewritten.size() == 2) {
            EGraphNode lhs = rewritten.get(0);
            EGraphNode rhs = rewritten.get(1);
            if (isNone(lhs)
                    && (isNone(rhs)
                            || EGraphNode.hasCompatibleRelationArity(lhs, rhs))) {
                return booleanConstant(node, true);
            }
            if (isNone(rhs) && isStaticallyNonemptySet(lhs, bindings)) {
                return booleanConstant(node, false);
            }
            if (isUniv(rhs)) {
                return booleanConstant(node, true);
            }
        }

        if (node.getOpcode() == Opcode.NOT_IN && rewritten.size() == 2) {
            EGraphNode lhs = rewritten.get(0);
            EGraphNode rhs = rewritten.get(1);
            if ((isNone(lhs)
                            && (isNone(rhs)
                                    || EGraphNode.hasCompatibleRelationArity(lhs, rhs)))
                    || isUniv(rhs)) {
                return booleanConstant(node, false);
            }
            if (isNone(rhs) && isStaticallyNonemptySet(lhs, bindings)) {
                return booleanConstant(node, true);
            }
        }

        if (rewritten.size() == 1 && isNone(rewritten.get(0))) {
            switch (node.getOpcode()) {
                case NO:
                case LONE:
                    return booleanConstant(node, true);
                case SOME:
                case ONE:
                    return booleanConstant(node, false);
                default:
                    break;
            }
        }
        return node;
    }

    private static boolean containsSemanticComplement(List<EGraphNode> nodes) {
        for (int left = 0; left < nodes.size(); left++) {
            for (int right = left + 1; right < nodes.size(); right++) {
                if (EGraphNode.areSemanticComplements(
                        nodes.get(left), nodes.get(right))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isUniv(EGraphNode node) {
        return EGraphNode.isSetConstant(node, "univ");
    }

    @LeanVerifiedRewrite({"R0-REL-028", "R0-REL-033"})
    private static EGraphNode normalizeRelationalUnaryAndIdentityRules(
            EGraphNode node,
            List<EGraphNode> rewritten,
            Map<String, QuantiVar> bindings) {
        if ((node.getOpcode() == Opcode.SOME
                        || node.getOpcode() == Opcode.NO)
                && rewritten.size() == 1) {
            EGraphNode difference =
                    EGraphNode.parserCertifiedDifferenceCardinalityExpansion(
                            node, node.getChildClasses().get(0));
            if (difference != null) {
                return normalizeGuardedSourceRules(difference, bindings);
            }
            EGraphNode expanded =
                    EGraphNode.parserCertifiedUnionCardinalityExpansion(
                            node, node.getChildClasses().get(0));
            if (expanded != null) {
                return expanded;
            }
        }

        if ((node.getOpcode() == Opcode.DOMAIN
                        || node.getOpcode() == Opcode.RANGE)
                && rewritten.size() == 2) {
            EGraphNode identity =
                    EGraphNode.parserCertifiedRestrictionIdentityOrZero(
                            node, node.getEClassRef());
            if (identity != null) {
                return identity;
            }
            EGraphNode unary =
                    EGraphNode.parserCertifiedUnaryRestrictionOrientation(
                            node, node.getEClassRef());
            if (unary != null) {
                return unary;
            }
            EGraphNode restriction = EGraphNode.parserCertifiedNestedRestriction(
                    node,
                    node.getChildClasses().get(0),
                    node.getChildClasses().get(1));
            if (restriction != null) {
                return restriction;
            }
        }

        if (node.getOpcode() == Opcode.JOIN && rewritten.size() == 2) {
            EGraphNode contextualRestriction =
                    EGraphNode.parserCertifiedJoinRestrictionNormalization(
                            node,
                            node.getChildClasses().get(0),
                            node.getChildClasses().get(1));
            if (contextualRestriction != null) {
                return normalizeGuardedSourceRules(
                        contextualRestriction, bindings);
            }
        }

        if (node.getOpcode() == Opcode.JOIN
                && rewritten.size() >= 2
                && EGraphNode.isExactRelationNode(node)
                && rewritten.stream().allMatch(EGraphNode::isExactRelationNode)) {
            List<EGraphNode> retained = new ArrayList<>(rewritten.size());
            for (EGraphNode child : rewritten) {
                if (!EGraphNode.isIdentityRelation(child)) {
                    retained.add(child);
                }
            }
            if (retained.size() != rewritten.size()) {
                if (retained.isEmpty()) {
                    return rewritten.get(0);
                }
                if (retained.size() == 1) {
                    return retained.get(0);
                }
                node.setNormalizedChildren(retained);
            }
            return node;
        }

        if (node.getOpcode() == Opcode.TRANSPOSE
                && rewritten.size() == 1
                && isExactBinaryRelation(node)) {
            EGraphNode empty =
                    EGraphNode.parserCertifiedEmptyRelationalUnaryIdentity(
                            node, node.getChildClasses().get(0));
            if (empty != null) {
                return empty;
            }
            EGraphNode child = rewritten.get(0);
            if (EGraphNode.isIdentityRelation(child)
                    && sameExactRelationOccurrence(node, child)) {
                return child;
            }
            if (child.getOpcode() == Opcode.TRANSPOSE
                    && child.getChildren().size() == 1
                    && isExactBinaryRelation(child)
                    && sameExactRelationOccurrence(
                            node, child.getChildren().get(0))) {
                return child.getChildren().get(0);
            }
            if (child.getOpcode() == Opcode.ARROW
                    && child.getChildren().size() == 2
                    && isExactBinaryRelation(child)) {
                EGraphNode reversed = copyShallow(child, Opcode.ARROW);
                reversed.setExactAlloyType(node.getExactAlloyType());
                reversed.setNormalizedChildren(List.of(
                        child.getChildren().get(1),
                        child.getChildren().get(0)));
                return reversed;
            }
            EGraphNode reversedJoin =
                    EGraphNode.parserCertifiedTransposeJoinReversal(
                            node, node.getChildClasses().get(0));
            if (reversedJoin != null) {
                return reversedJoin;
            }
            EGraphNode closure =
                    EGraphNode.parserCertifiedTransposeClosureCommutation(
                            node, node.getChildClasses().get(0));
            if (closure != null) {
                return normalizeGuardedSourceRules(closure, bindings);
            }
            EGraphNode restriction =
                    EGraphNode.parserCertifiedTransposeRestrictionSwap(
                            node, node.getChildClasses().get(0));
            if (restriction != null) {
                return normalizeGuardedSourceRules(restriction, bindings);
            }
            EGraphNode distributed = distributeTransposeThroughContainer(
                    node, child);
            if (distributed != null) {
                return distributed;
            }
        }

        if ((node.getOpcode() == Opcode.CLOSURE
                        || node.getOpcode() == Opcode.RCLOSURE)
                && rewritten.size() == 1
                && isExactBinaryRelation(node)) {
            EGraphNode empty =
                    EGraphNode.parserCertifiedEmptyRelationalUnaryIdentity(
                            node, node.getChildClasses().get(0));
            if (empty != null) {
                return empty;
            }
            EGraphNode child = rewritten.get(0);
            if (EGraphNode.isIdentityRelation(child)
                    && sameExactRelationOccurrence(node, child)) {
                return child;
            }
            if ((child.getOpcode() == Opcode.CLOSURE
                            || child.getOpcode() == Opcode.RCLOSURE)
                    && child.getChildren().size() == 1
                    && isExactBinaryRelation(child)) {
                Opcode resultOpcode = node.getOpcode() == Opcode.RCLOSURE
                                || child.getOpcode() == Opcode.RCLOSURE
                        ? Opcode.RCLOSURE : Opcode.CLOSURE;
                if (child.getOpcode() == resultOpcode
                        && sameExactRelationOccurrence(node, child)) {
                    return child;
                }
                EGraphNode collapsed = copyShallow(node, resultOpcode);
                collapsed.setNormalizedChildren(Collections.singletonList(
                        child.getChildren().get(0)));
                return collapsed;
            }
        }
        return node;
    }

    @LeanVerifiedRewrite("R0-REL-001")
    private static EGraphNode distributeTransposeThroughContainer(
            EGraphNode transpose,
            EGraphNode container) {
        EGraphNode distributed = buildTransposeContainer(
                transpose,
                container,
                transpose.getExactAlloyType(),
                -30);
        if (distributed != null) {
            distributed.preserveSourceOccurrenceLineageFrom(transpose);
        }
        return distributed;
    }

    private static EGraphNode buildTransposeContainer(
            EGraphNode owner,
            EGraphNode container,
            is.fivefivefive.ACGN.alloy.ExactAlloyType resultType,
            int salt) {
        Opcode opcode = container.getOpcode();
        boolean aciContainer = opcode == Opcode.PLUS
                || opcode == Opcode.INTERSECT;
        if ((!aciContainer && opcode != Opcode.MINUS)
                || !isExactBinaryRelation(container)
                || (aciContainer && (!container.isSetFlexibleArity()
                        || container.getChildren().size() < 2))
                || (opcode == Opcode.MINUS
                        && container.getChildren().size() != 2)) {
            return null;
        }
        List<EGraphNode> transposedOperands = new ArrayList<>(
                container.getChildren().size());
        int index = 0;
        for (EGraphNode operand : container.getChildren()) {
            EGraphNode derived = buildNormalizedTransposeOperand(
                    owner, operand, salt - index++ * 17);
            if (derived == null) {
                return null;
            }
            transposedOperands.add(derived);
        }
        EGraphNode distributed = copyShallow(container, opcode);
        distributed.setExactAlloyType(resultType);
        distributed.setNormalizedChildren(transposedOperands);
        return distributed;
    }

    private static EGraphNode buildNormalizedTransposeOperand(
            EGraphNode owner,
            EGraphNode operand,
            int salt) {
        if (!isExactBinaryRelation(operand)) {
            return null;
        }
        is.fivefivefive.ACGN.alloy.ExactAlloyType exact;
        try {
            exact = is.fivefivefive.ACGN.alloy.ExactAlloyType
                    .parserCertifiedTranspose(operand.getExactAlloyType());
        } catch (IllegalArgumentException rejectedProof) {
            return null;
        }
        if (!owner.getExactAlloyType().sharesParserModuleAuthorityWith(exact)) {
            return null;
        }
        if (operand.getOpcode() == Opcode.ARROW
                && operand.getChildren().size() == 2) {
            EGraphNode reversed = copyShallow(operand, Opcode.ARROW);
            reversed.setExactAlloyType(exact);
            reversed.setNormalizedChildren(List.of(
                    operand.getChildren().get(1),
                    operand.getChildren().get(0)));
            return reversed;
        }
        EGraphNode distributed = buildTransposeContainer(
                owner, operand, exact, salt - 1);
        if (distributed != null) {
            return distributed;
        }
        EGraphNode transpose = EGraphNode.inOwningArena(
                owner,
                syntheticId(owner, salt),
                Opcode.TRANSPOSE,
                new ArrayList<>(),
                false,
                1,
                false,
                owner.getMetatype(),
                owner.getSemanticProfile());
        transpose.setSourceName("UNOPE_TRANSPOSE");
        transpose.setSourceType(operand.getSourceType());
        transpose.setExactAlloyType(exact);
        transpose.addNormalizedChild(operand);
        return transpose;
    }

    private static boolean isExactBinaryRelation(EGraphNode node) {
        is.fivefivefive.ACGN.alloy.ExactAlloyType exact =
                node == null ? null : node.getExactAlloyType();
        return exact != null
                && (exact.kind()
                                == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                        || exact.kind()
                                == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.EMPTY_RELATION)
                && exact.relationArity() == 2;
    }

    private static boolean sameExactRelationOccurrence(
            EGraphNode left,
            EGraphNode right) {
        is.fivefivefive.ACGN.alloy.ExactAlloyType leftType =
                left == null ? null : left.getExactAlloyType();
        is.fivefivefive.ACGN.alloy.ExactAlloyType rightType =
                right == null ? null : right.getExactAlloyType();
        return leftType != null
                && rightType != null
                && (leftType.kind()
                                == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                        || leftType.kind()
                                == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.EMPTY_RELATION)
                && leftType.sameOccurrenceEvidenceAs(rightType);
    }

    @LeanVerifiedRewrite("R0-REL-032")
    private static List<EGraphNode> removeSubrelationsCoveredByFullCarriers(
            List<EGraphNode> children) {
        List<EGraphNode> retained = new ArrayList<>(children.size());
        for (int candidateIndex = 0;
                candidateIndex < children.size();
                candidateIndex++) {
            EGraphNode candidate = children.get(candidateIndex);
            EGraphNode pruned = pruneSubrelationsCoveredBySiblingCarriers(
                    candidate, candidateIndex, children);
            if (pruned != null) {
                retained.add(pruned);
            }
        }
        return retained;
    }

    private static EGraphNode pruneSubrelationsCoveredBySiblingCarriers(
            EGraphNode candidate,
            int candidateIndex,
            List<EGraphNode> siblings) {
        if (isCoveredBySiblingFullCarrier(candidate, candidateIndex, siblings)) {
            return null;
        }
        if (candidate.getOpcode() != Opcode.PLUS) {
            return candidate;
        }
        List<EGraphNode> retained = new ArrayList<>(candidate.getChildren().size());
        for (EGraphNode child : candidate.getChildren()) {
            EGraphNode pruned = pruneSubrelationsCoveredBySiblingSignatures(
                    child, siblings);
            if (pruned != null) {
                retained.add(pruned);
            }
        }
        if (retained.isEmpty()) {
            return null;
        }
        if (retained.size() == 1) {
            return retained.get(0);
        }
        if (retained.size() != candidate.getChildren().size()) {
            candidate.setNormalizedChildren(retained);
        }
        return candidate;
    }

    private static EGraphNode pruneSubrelationsCoveredBySiblingSignatures(
            EGraphNode candidate,
            List<EGraphNode> siblings) {
        for (EGraphNode carrier : siblings) {
            if (EGraphNode.isParserCertifiedSubrelationOfFullSignature(
                    candidate, carrier)) {
                return null;
            }
        }
        if (candidate.getOpcode() != Opcode.PLUS) {
            return candidate;
        }
        List<EGraphNode> retained = new ArrayList<>(candidate.getChildren().size());
        for (EGraphNode child : candidate.getChildren()) {
            EGraphNode pruned = pruneSubrelationsCoveredBySiblingSignatures(
                    child, siblings);
            if (pruned != null) {
                retained.add(pruned);
            }
        }
        if (retained.isEmpty()) {
            return null;
        }
        if (retained.size() == 1) {
            return retained.get(0);
        }
        if (retained.size() != candidate.getChildren().size()) {
            candidate.setNormalizedChildren(retained);
        }
        return candidate;
    }

    private static boolean isCoveredBySiblingFullCarrier(
            EGraphNode candidate,
            int candidateIndex,
            List<EGraphNode> siblings) {
        for (int carrierIndex = 0;
                carrierIndex < siblings.size();
                carrierIndex++) {
            if (carrierIndex == candidateIndex) {
                continue;
            }
            if (EGraphNode.isParserCertifiedSubrelationOfFullCarrier(
                            candidate, siblings.get(carrierIndex))
                    && (!EGraphNode.isParserCertifiedSubrelationOfFullCarrier(
                                    siblings.get(carrierIndex), candidate)
                            || (candidateIndex >= 0
                                    && candidateIndex > carrierIndex))) {
                return true;
            }
        }
        return false;
    }

    @LeanVerifiedRewrite("R0-REL-032")
    private static List<EGraphNode> removeFullCarriersContainingSubrelations(
            List<EGraphNode> children) {
        List<EGraphNode> retained = new ArrayList<>(children.size());
        for (int carrierIndex = 0;
                carrierIndex < children.size();
                carrierIndex++) {
            EGraphNode carrier = children.get(carrierIndex);
            boolean redundant = false;
            for (int candidateIndex = 0;
                    candidateIndex < children.size();
                    candidateIndex++) {
                if (candidateIndex == carrierIndex) {
                    continue;
                }
                if (EGraphNode.isParserCertifiedSubrelationOfFullCarrier(
                                children.get(candidateIndex), carrier)
                        && (!EGraphNode.isParserCertifiedSubrelationOfFullCarrier(
                                        carrier, children.get(candidateIndex))
                                || carrierIndex > candidateIndex)) {
                    redundant = true;
                    break;
                }
            }
            if (!redundant) {
                retained.add(carrier);
            }
        }
        return retained;
    }

    private static Boolean booleanConstantValue(EGraphNode node) {
        if (EGraphNode.isBooleanConstant(node, true)) {
            return Boolean.TRUE;
        }
        if (EGraphNode.isBooleanConstant(node, false)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean isStaticallyNonemptySet(
            EGraphNode node,
            Map<String, QuantiVar> bindings) {
        if (node == null) {
            return false;
        }
        if (node.getOpcode() != Opcode.VARIABLE) {
            return false;
        }
        QuantiVar variable = bindings.get(node.getAlphaName());
        if (variable == null) {
            variable = bindings.get(node.getSourceName());
        }
        if (variable == null) {
            variable = bindings.get(bindingKey(node));
        }
        if (variable == null) {
            return false;
        }
        Cardinality cardinality = variable.getCardinality();
        return cardinalityGuaranteesNonempty(cardinality);
    }

    private static boolean cardinalityGuaranteesNonempty(Cardinality cardinality) {
        return cardinality == Cardinality.ONE || cardinality == Cardinality.SOME;
    }

    private static String primitiveVarType(String type) {
        String normalized = normalizeType(type);
        return normalized.isEmpty() ? type : normalized;
    }

    private static boolean needsDomainConstraint(DomainDescriptor domain, String primitiveType) {
        if (domain == null || domain.domain == null) {
            return false;
        }
        String primitiveDomain = primitiveDomainName(domain.domain);
        if (primitiveDomain != null && primitiveType != null) {
            return !normalizeType(primitiveDomain).equals(normalizeType(primitiveType));
        }
        return true;
    }

    private static boolean domainUsesPrimitiveCarrier(
            EGraphNode domain,
            String primitiveType) {
        if (domain == null || primitiveType == null) {
            return false;
        }
        is.fivefivefive.ACGN.alloy.ExactAlloyType exact = domain.getExactAlloyType();
        if (exact != null
                && exact.kind()
                        == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                && exact.relationArity() == 1
                && !exact.alternatives().isEmpty()) {
            String required = normalizeType(primitiveType);
            for (List<String> alternative : exact.alternatives()) {
                if (alternative.size() != 1
                        || !normalizeType(alternative.get(0)).equals(required)) {
                    return false;
                }
            }
            return true;
        }
        String primitiveDomain = primitiveDomainName(domain);
        return primitiveDomain != null
                && normalizeType(primitiveDomain).equals(normalizeType(primitiveType));
    }

    private static DomainDescriptor domainDescriptor(EGraphNode typeEGraph) {
        if (typeEGraph == null) {
            return new DomainDescriptor(null, Cardinality.ONE);
        }
        Cardinality cardinality = cardinalityOf(typeEGraph.getOpcode());
        if (cardinality != null && typeEGraph.getChildren().size() == 1) {
            return new DomainDescriptor(typeEGraph.getChildren().get(0), cardinality);
        }
        // Alloy declaration domains default to exactly one value. Explicit
        // set/lone/some/one wrappers above override this source-language default.
        return new DomainDescriptor(typeEGraph, Cardinality.ONE);
    }

    private static String primitiveDomainName(EGraphNode typeEGraph) {
        if (typeEGraph == null) {
            return null;
        }
        if (typeEGraph.getOpcode() == Opcode.GLOBALBINDING || typeEGraph.getOpcode() == Opcode.CONSTANT) {
            return firstNonEmpty(typeEGraph.getSourceName(), typeEGraph.getSourceType());
        }
        return null;
    }

    private static Cardinality cardinalityOf(Opcode opcode) {
        switch (opcode) {
            case ONE:
                return Cardinality.ONE;
            case SOME:
                return Cardinality.SOME;
            case LONE:
                return Cardinality.LONE;
            case EXACTLY:
                return Cardinality.EXACTLY;
            case SETOF:
                return Cardinality.SET;
            default:
                return null;
        }
    }

    private static boolean isNone(EGraphNode node) {
        return EGraphNode.isSetConstant(node, "none");
    }

    private static boolean hasEmptyAdmissibleBindingSet(DomainDescriptor domain) {
        if (domain == null || !isStaticallyEmptySet(domain.domain)) {
            return false;
        }
        return domain.cardinality == Cardinality.SOME
                || domain.cardinality == Cardinality.ONE;
    }

    private static boolean isStaticallyEmptySet(EGraphNode node) {
        if (node == null) {
            return false;
        }
        if (isNone(node)) {
            return true;
        }
        if (node.getOpcode() == Opcode.INTERSECT) {
            for (EGraphNode child : node.getChildren()) {
                if (isStaticallyEmptySet(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node.getOpcode() == Opcode.MINUS && node.getChildren().size() == 2) {
            EGraphNode left = node.getChildren().get(0);
            EGraphNode right = node.getChildren().get(1);
            return isStaticallyEmptySet(left)
                    || EGraphNode.sameSemanticInvocation(left, right);
        }
        return false;
    }

    private static EGraphNode domainConstraint(QuantiVar qv, EGraphNode sourceVariable, EGraphNode domain) {
        EGraphNode constraint = syntheticNode(sourceVariable, Opcode.IN, -1);
        EGraphNode variable = EGraphNode.inOwningArena(
                sourceVariable,
                sourceVariable.getId(),
                Opcode.VARIABLE,
                new ArrayList<>(),
                false,
                0,
                false,
                Metatype.ATOMIC,
                sourceVariable.getSemanticProfile());
        variable.setSourceName(sourceVariable.getSourceName());
        variable.setSourceType(qv.getTypeName());
        variable.setExactAlloyType(
                is.fivefivefive.ACGN.alloy.ExactAlloyType.unaryRelation(
                        qv.getTypeName()));
        variable.setAlphaName(qv.getName());
        constraint.addNormalizedChild(variable);
        constraint.addNormalizedChild(cloneEGraph(domain));
        return constraint;
    }

    @LeanVerifiedRewrite({"R0-CORE-007", "R0-CORE-008", "R0-CORE-009"})
    private static EGraphNode toNNF(EGraphNode node, boolean negated) {
        if (node == null) {
            return null;
        }
        Opcode opcode = node.getOpcode();
        if (opcode == Opcode.END) {
            return null;
        }
        boolean booleanIte = opcode == Opcode.ITE;
        if ((isBooleanBranchConnective(opcode) || booleanIte)
                && (!EGraphNode.hasBooleanRewriteAuthority(node)
                        || !EGraphNode.hasBooleanOperands(node))) {
            EGraphNode retained = copyShallow(node, opcode);
            for (EGraphNode child : node.getChildren()) {
                EGraphNode retainedChild = toNNF(child, false);
                if (retainedChild != null) {
                    retained.addNormalizedChild(retainedChild);
                }
            }
            return negated
                    ? syntheticUnary(node, Opcode.NOT, retained, -1)
                    : retained;
        }
        if (opcode == Opcode.NOT && node.getChildren().size() == 1) {
            return toNNF(node.getChildren().get(0), !negated);
        }
        if (isQuantifierNode(node)) {
            Opcode rewrittenOpcode = opcode;
            boolean negateBody = false;
            boolean retainOuterNegation = negated;
            if (negated) {
                switch (opcode) {
                    case FORALL:
                        rewrittenOpcode = Opcode.EXISTS;
                        negateBody = true;
                        retainOuterNegation = false;
                        break;
                    case EXISTS:
                        rewrittenOpcode = Opcode.FORALL;
                        negateBody = true;
                        retainOuterNegation = false;
                        break;
                    case NO:
                        rewrittenOpcode = Opcode.EXISTS;
                        retainOuterNegation = false;
                        break;
                    default:
                        break;
                }
            }
            EGraphNode rewritten = copyQuantifierShallow(node, rewrittenOpcode);
            for (EGraphNode child : node.getChildren()) {
                EGraphNode rewrittenChild = toNNF(
                        child, negateBody && !isRelDecl(child.getOpcode()));
                if (rewrittenChild != null) {
                    rewritten.addNormalizedChild(rewrittenChild);
                }
            }
            return retainOuterNegation
                    ? syntheticUnary(node, Opcode.NOT, rewritten, -1)
                    : rewritten;
        }
        if (opcode == Opcode.IFF
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 2) {
            return toNNF(expandIff(node, negated), false);
        }
        if (opcode == Opcode.IMPLIES
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 2) {
            EGraphNode left = node.getChildren().get(0);
            EGraphNode right = node.getChildren().get(1);
            if (negated) {
                EGraphNode conjunction = syntheticNode(node, Opcode.AND, -1);
                conjunction.addNormalizedChild(toNNF(left, false));
                conjunction.addNormalizedChild(toNNF(right, true));
                return conjunction;
            }
            EGraphNode disjunction = syntheticNode(node, Opcode.OR, -1);
            disjunction.addNormalizedChild(toNNF(left, true));
            disjunction.addNormalizedChild(toNNF(right, false));
            return disjunction;
        }
        if (opcode == Opcode.ITE
                && EGraphNode.hasBooleanRewriteAuthority(node)
                && EGraphNode.hasBooleanOperands(node)
                && node.getChildren().size() == 3) {
            return toNNF(expandIte(node, negated), false);
        }
        if (opcode == Opcode.AND || opcode == Opcode.OR) {
            EGraphNode rewritten = copyShallow(node, negated ? dualBooleanOpcode(opcode) : opcode);
            for (EGraphNode child : node.getChildren()) {
                EGraphNode rewrittenChild = toNNF(child, negated);
                if (rewrittenChild != null) {
                    rewritten.addNormalizedChild(rewrittenChild);
                }
            }
            return rewritten;
        }
        Opcode dual = negated ? dualOpcode(opcode) : opcode;
        if (dual != null) {
            EGraphNode rewritten = copyShallow(node, dual);
            for (EGraphNode child : node.getChildren()) {
                EGraphNode rewrittenChild = toNNF(child, negated && dualNegatesChildren(opcode));
                if (rewrittenChild != null) {
                    rewritten.addNormalizedChild(rewrittenChild);
                }
            }
            return rewritten;
        }
        if (negated) {
            return syntheticUnary(node, Opcode.NOT, toNNF(node, false), -1);
        }
        EGraphNode rewritten = copyShallow(node, opcode);
        for (EGraphNode child : node.getChildren()) {
            EGraphNode rewrittenChild = toNNF(child, false);
            if (rewrittenChild != null) {
                rewritten.addNormalizedChild(rewrittenChild);
            }
        }
        return rewritten;
    }

    @LeanVerifiedRewrite("R0-CORE-002")
    private static EGraphNode removeEndNodes(EGraphNode node) {
        if (node == null || node.getOpcode() == Opcode.END) {
            return null;
        }
        List<EGraphNode> rewrittenChildren = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            EGraphNode rewrittenChild = removeEndNodes(child);
            if (rewrittenChild != null) {
                rewrittenChildren.add(rewrittenChild);
            }
        }
        node.setNormalizedChildren(rewrittenChildren);
        if (isAssociative(node.getOpcode())
                && node.getChildren().size() == 1
                && (!isBooleanBranchConnective(node.getOpcode())
                        || (EGraphNode.hasBooleanRewriteAuthority(node)
                                && EGraphNode.hasBooleanOperands(node)))) {
            return node.getChildren().get(0);
        }
        if (isUnaryOperator(node.getOpcode()) && node.getChildren().isEmpty()) {
            return null;
        }
        if (isBinaryOperator(node.getOpcode()) && node.getChildren().size() < 2) {
            return null;
        }
        if (isDanglingStructuralMarker(node)) {
            return null;
        }
        return node;
    }

    private static boolean isDanglingStructuralMarker(EGraphNode node) {
        if (!node.getChildren().isEmpty()) {
            return false;
        }
        switch (node.getOpcode()) {
            case VARIABLE:
            case GLOBALBINDING:
            case CONSTANT:
            case CALL:
            case REF:
            case LET:
            case PREDICATE:
            case FUNCTION:
                return false;
            default:
                return true;
        }
    }

    private static boolean isUnaryOperator(Opcode opcode) {
        return opcode == Opcode.NOT || opcode == Opcode.SOME || opcode == Opcode.NO || opcode == Opcode.LONE
                || opcode == Opcode.ONE || opcode == Opcode.SETOF || opcode == Opcode.EXACTLY
                || opcode == Opcode.TRANSPOSE || opcode == Opcode.RCLOSURE || opcode == Opcode.CLOSURE
                || opcode == Opcode.CARDINALITY || opcode == Opcode.CAST2INT || opcode == Opcode.CAST2SIGINT
                || opcode == Opcode.PRIME || opcode == Opcode.BEFORE || opcode == Opcode.HISTORICALLY
                || opcode == Opcode.ONCE || opcode == Opcode.ALWAYS || opcode == Opcode.EVENTUALLY
                || opcode == Opcode.AFTER;
    }

    private static boolean isBinaryOperator(Opcode opcode) {
        switch (opcode) {
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
            case JOIN:
            case ARROW:
            case ANY_ARROW_SOME:
            case ANY_ARROW_ONE:
            case ANY_ARROW_LONE:
            case SOME_ARROW_ANY:
            case SOME_ARROW_SOME:
            case SOME_ARROW_ONE:
            case SOME_ARROW_LONE:
            case ONE_ARROW_ANY:
            case ONE_ARROW_SOME:
            case ONE_ARROW_ONE:
            case ONE_ARROW_LONE:
            case LONE_ARROW_ANY:
            case LONE_ARROW_SOME:
            case LONE_ARROW_ONE:
            case LONE_ARROW_LONE:
            case ISSEQ_ARROW_LONE:
            case DOMAIN:
            case RANGE:
            case PLUSPLUS:
            case MINUS:
            case IMINUS:
            case DIV:
            case REM:
            case SHL:
            case SHA:
            case SHR:
            case UNTIL:
            case RELEASES:
            case SINCE:
            case TRIGGERED:
                return true;
            default:
                return false;
        }
    }

    @LeanVerifiedRewrite({"R0-CORE-017", "R0-CORE-018", "R0-CORE-019"})
    private static EGraphNode normalizeAssociativeCommutative(EGraphNode node) {
        if (node == null) {
            return null;
        }
        List<EGraphNode> rewrittenChildren = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            EGraphNode normalizedChild = normalizeAssociativeCommutative(child);
            if (normalizedChild == null) {
                continue;
            }
            if (node.sameFlatOperatorInstance(normalizedChild)) {
                rewrittenChildren.addAll(normalizedChild.getChildren());
            } else {
                rewrittenChildren.add(normalizedChild);
            }
        }
        if (node.isOrderInsensitive()) {
            Collections.sort(rewrittenChildren, Comparator.comparing(NormalForm::sortKey));
        }
        node.setNormalizedChildren(rewrittenChildren);
        return node;
    }

    /**
     * Exposes each maximal Boolean ACI region to the slot allocator without
     * applying commutativity or idempotence before certification. Quantifier,
     * temporal, and all other operator nodes remain explicit barriers.
     */
    @LeanVerifiedRewrite("R0-CORE-017")
    private static EGraphNode flattenBooleanAssociationForPrenex(
            EGraphNode node) {
        if (node == null) {
            return null;
        }
        EGraphNode flattened = copyShallow(node, node.getOpcode());
        for (EGraphNode child : node.getChildren()) {
            EGraphNode flattenedChild = flattenBooleanAssociationForPrenex(child);
            if (flattenedChild != null
                    && (node.getOpcode() == Opcode.AND
                            || node.getOpcode() == Opcode.OR)
                    && node.sameFlatOperatorInstance(flattenedChild)) {
                for (EGraphNode grandchild : flattenedChild.getChildren()) {
                    flattened.addNormalizedChild(grandchild);
                }
            } else if (flattenedChild != null) {
                flattened.addNormalizedChild(flattenedChild);
            }
        }
        return flattened;
    }

    @LeanVerifiedRewrite("R0-CORE-005")
    private static EGraphNode expandIff(EGraphNode node, boolean negated) {
        EGraphNode left = node.getChildren().get(0);
        EGraphNode right = node.getChildren().get(1);
        if (negated) {
            EGraphNode leftAndNotRight = syntheticNode(node, Opcode.AND, -1);
            leftAndNotRight.addNormalizedChild(cloneEGraph(left));
            leftAndNotRight.addNormalizedChild(syntheticUnary(node, Opcode.NOT, cloneEGraph(right), -2));

            EGraphNode rightAndNotLeft = syntheticNode(node, Opcode.AND, -3);
            rightAndNotLeft.addNormalizedChild(cloneEGraph(right));
            rightAndNotLeft.addNormalizedChild(syntheticUnary(node, Opcode.NOT, cloneEGraph(left), -4));

            EGraphNode disjunction = syntheticNode(node, Opcode.OR, -5);
            disjunction.addNormalizedChild(leftAndNotRight);
            disjunction.addNormalizedChild(rightAndNotLeft);
            return disjunction;
        }

        EGraphNode leftImpliesRight = syntheticNode(node, Opcode.IMPLIES, -1);
        leftImpliesRight.addNormalizedChild(cloneEGraph(left));
        leftImpliesRight.addNormalizedChild(cloneEGraph(right));

        EGraphNode rightImpliesLeft = syntheticNode(node, Opcode.IMPLIES, -2);
        rightImpliesLeft.addNormalizedChild(cloneEGraph(right));
        rightImpliesLeft.addNormalizedChild(cloneEGraph(left));

        EGraphNode conjunction = syntheticNode(node, Opcode.AND, -3);
        conjunction.addNormalizedChild(leftImpliesRight);
        conjunction.addNormalizedChild(rightImpliesLeft);
        return conjunction;
    }

    @LeanVerifiedRewrite("R0-CORE-006")
    private static EGraphNode expandIte(EGraphNode node, boolean negated) {
        EGraphNode condition = node.getChildren().get(0);
        EGraphNode thenBranch = node.getChildren().get(1);
        EGraphNode elseBranch = node.getChildren().get(2);

        EGraphNode thenCase = syntheticNode(node, Opcode.AND, -1);
        thenCase.addNormalizedChild(cloneEGraph(condition));
        thenCase.addNormalizedChild(cloneEGraph(thenBranch));

        EGraphNode elseCase = syntheticNode(node, Opcode.AND, -2);
        elseCase.addNormalizedChild(syntheticUnary(node, Opcode.NOT, cloneEGraph(condition), -3));
        elseCase.addNormalizedChild(cloneEGraph(elseBranch));

        EGraphNode expanded = syntheticNode(node, Opcode.OR, -4);
        expanded.addNormalizedChild(thenCase);
        expanded.addNormalizedChild(elseCase);
        if (negated) {
            return syntheticUnary(node, Opcode.NOT, expanded, -5);
        }
        return expanded;
    }

    private static EGraphNode syntheticUnary(EGraphNode source, Opcode opcode, EGraphNode child, int offset) {
        EGraphNode node = syntheticNode(source, opcode, offset);
        node.addNormalizedChild(child);
        return node;
    }

    private static EGraphNode syntheticNode(EGraphNode source, Opcode opcode, int offset) {
        EGraphNode synthetic = EGraphNode.inOwningArena(
                source,
                syntheticId(source, offset),
                opcode,
                new ArrayList<>(),
                isCommutative(opcode),
                maxArity(opcode),
                isFlexibleArity(opcode),
                Metatype.BOOLEAN,
                source.getSemanticProfile());
        synthetic.setSourceType("Bool");
        synthetic.setExactAlloyType(
                is.fivefivefive.ACGN.alloy.ExactAlloyType.boolType());
        if (isBooleanBranchConnective(opcode) || opcode == Opcode.ITE) {
            synthetic.markDerivedBooleanRewriteAuthority();
        }
        return synthetic;
    }

    private static EGraphNode copyShallow(EGraphNode source, Opcode opcode) {
        boolean sameOpcode = source.getOpcode() == opcode;
        EGraphNode copy = EGraphNode.inOwningArena(
                source,
                source.getId(),
                opcode,
                new ArrayList<>(),
                sameOpcode ? source.isCommutative() : isCommutative(opcode),
                source.getMaxArity(),
                source.isFlexibleArity(),
                source.getMetatype(),
                source.getSemanticProfile());
        copy.setSourceName(sameOpcode ? source.getSourceName() : null);
        copy.setSourceType(sameOpcode || !isBooleanBranchConnective(opcode)
                ? source.getSourceType() : "Bool");
        copy.setExactAlloyType(sameOpcode || !isBooleanBranchConnective(opcode)
                ? source.getExactAlloyType()
                : is.fivefivefive.ACGN.alloy.ExactAlloyType.boolType());
        copy.setAlphaName(sameOpcode ? source.getAlphaName() : null);
        if (sameOpcode) {
            copy.preserveSourceOccurrenceLineageFrom(source);
        }
        copy.setCallOccurrenceId(sameOpcode ? source.getCallOccurrenceId() : -1L);
        copy.setDeclaredArity(sameOpcode ? source.getDeclaredArity() : -1);
        copy.setCallArityAuthority(sameOpcode ? source.getCallArityAuthority() : null);
        if (sameOpcode) {
            copy.preserveTemporalReferenceAuthorityFrom(source);
            copy.preserveTemporalSnapshotBindingFrom(source);
        }
        if (sameOpcode) {
            copy.preserveDerivedBooleanRewriteAuthorityFrom(source);
        } else if (isBooleanBranchConnective(opcode) || opcode == Opcode.ITE) {
            copy.markDerivedBooleanRewriteAuthority();
        }
        if (sameOpcode) {
            // Install semantic identity authority last: ordinary metadata setters
            // intentionally revoke reserved built-in authority.
            copy.preserveSemanticIdentityFrom(source);
            copy.preserveParserSignatureEvidenceFrom(source);
        }
        return copy;
    }

    private static EGraphNode copyQuantifierShallow(EGraphNode source, Opcode opcode) {
        if (source.getOpcode() == opcode) {
            return copyShallow(source, opcode);
        }
        EGraphNode copy = EGraphNode.inOwningArena(
                source,
                source.getId(),
                opcode,
                new ArrayList<>(),
                false,
                source.getMaxArity(),
                source.isFlexibleArity(),
                source.getMetatype(),
                source.getSemanticProfile());
        copy.setSourceType(source.getSourceType());
        copy.setExactAlloyType(source.getExactAlloyType());
        return copy;
    }

    private static boolean isCommutative(Opcode opcode) {
        return opcode == Opcode.AND || opcode == Opcode.OR || opcode == Opcode.IFF
                || opcode == Opcode.EQUALS || opcode == Opcode.NOT_EQUALS
                || opcode == Opcode.INTERSECT || opcode == Opcode.PLUS || opcode == Opcode.MUL
                || opcode == Opcode.IPLUS;
    }

    private static boolean isAssociative(Opcode opcode) {
        return AlloyOperatorPolicy.isFlatSetOperator(opcode);
    }

    private static boolean isBooleanBranchConnective(Opcode opcode) {
        return opcode == Opcode.NOT
                || opcode == Opcode.AND
                || opcode == Opcode.OR
                || opcode == Opcode.IMPLIES
                || opcode == Opcode.IFF;
    }

    private static int maxArity(Opcode opcode) {
        if (opcode == Opcode.CONSTANT) {
            return 0;
        }
        if (opcode == Opcode.NOT) {
            return 1;
        }
        return isFlexibleArity(opcode) ? -1 : 2;
    }

    private static boolean isFlexibleArity(Opcode opcode) {
        return isAssociative(opcode);
    }

    private static String sortKey(EGraphNode node) {
        StringBuilder sb = new StringBuilder();
        appendSortKey(node, sb);
        return sb.toString();
    }

    private static void appendSortKey(EGraphNode node, StringBuilder sb) {
        sb.append(node.getOpcode()).append('{')
                .append(node.getSemanticProfile().fingerprint()).append(';')
                .append(node.getExactAlloyType() == null
                        ? "" : node.getExactAlloyType().stableString()).append(';')
                .append(node.getArityPolicy()).append(';')
                .append(node.getSiblingQuotient()).append(';')
                .append(node.getFlatLicense()).append(';')
                .append(node.getUnitLicense()).append("}:");
        if (node.getOpcode() == Opcode.CALL) {
            sb.append(CallMetadata.semanticKey(node));
        } else if (node.getOpcode() == Opcode.VARIABLE
                && node.getAlphaName() != null) {
            sb.append(node.getAlphaName());
        } else if (node.getSemanticIdentity() != null) {
            sb.append(node.getSemanticIdentity());
        } else if (node.getAlphaName() != null) {
            sb.append(node.getAlphaName());
        } else if (node.getSourceName() != null) {
            sb.append(node.getSourceName());
        } else if (node.getChildren().isEmpty()) {
            sb.append(node.getSourceType() == null ? "" : node.getSourceType());
        }
        sb.append('[');
        for (EGraphNode child : node.getChildren()) {
            appendSortKey(child, sb);
            sb.append(',');
        }
        sb.append(']');
    }

    private static Opcode dualBooleanOpcode(Opcode opcode) {
        return opcode == Opcode.AND ? Opcode.OR : Opcode.AND;
    }

    @LeanVerifiedRewrite({"R0-CORE-009", "R0-CORE-010"})
    private static Opcode dualOpcode(Opcode opcode) {
        switch (opcode) {
            case EQUALS:
                return Opcode.NOT_EQUALS;
            case NOT_EQUALS:
                return Opcode.EQUALS;
            case GT:
                return Opcode.LTE;
            case GTE:
                return Opcode.LT;
            case IN:
                return Opcode.NOT_IN;
            case LT:
                return Opcode.GTE;
            case LTE:
                return Opcode.GT;
            case NOT_GT:
                return Opcode.GT;
            case NOT_GTE:
                return Opcode.GTE;
            case NOT_IN:
                return Opcode.IN;
            case NOT_LT:
                return Opcode.LT;
            case NOT_LTE:
                return Opcode.LTE;
            case SOME:
                return Opcode.NO;
            case NO:
                return Opcode.SOME;
            case ALWAYS:
                return Opcode.EVENTUALLY;
            case EVENTUALLY:
                return Opcode.ALWAYS;
            case HISTORICALLY:
                return Opcode.ONCE;
            case ONCE:
                return Opcode.HISTORICALLY;
            case UNTIL:
                return Opcode.RELEASES;
            case RELEASES:
                return Opcode.UNTIL;
            case SINCE:
                return Opcode.TRIGGERED;
            case TRIGGERED:
                return Opcode.SINCE;
            default:
                return null;
        }
    }

    private static boolean dualNegatesChildren(Opcode opcode) {
        return opcode == Opcode.ALWAYS || opcode == Opcode.EVENTUALLY
                || opcode == Opcode.HISTORICALLY || opcode == Opcode.ONCE
                || opcode == Opcode.UNTIL || opcode == Opcode.RELEASES
                || opcode == Opcode.SINCE || opcode == Opcode.TRIGGERED;
    }

    private static int syntheticId(EGraphNode source, int offset) {
        return -Math.abs(source.getId()) * 16 + offset;
    }

    private static EGraphNode cloneEGraph(EGraphNode node) {
        EGraphNode clone = EGraphNode.inOwningArena(
                node,
                node.getId(),
                node.getOpcode(),
                new ArrayList<>(),
                node.isCommutative(),
                node.getMaxArity(),
                node.isFlexibleArity(),
                node.getMetatype(),
                node.getSemanticProfile());
        clone.setSourceName(node.getSourceName());
        clone.setSourceType(node.getSourceType());
        clone.setExactAlloyType(node.getExactAlloyType());
        clone.setAlphaName(node.getAlphaName());
        clone.preserveSourceOccurrenceLineageFrom(node);
        clone.setCallOccurrenceId(node.getCallOccurrenceId());
        clone.setDeclaredArity(node.getDeclaredArity());
        clone.setCallArityAuthority(node.getCallArityAuthority());
        clone.preserveTemporalReferenceAuthorityFrom(node);
        clone.preserveDerivedBooleanRewriteAuthorityFrom(node);
        for (EGraphNode child : node.getChildren()) {
            clone.addNormalizedChild(cloneEGraph(child));
        }
        // Install semantic identity authority after all public metadata and child
        // mutations, each of which deliberately revokes reserved authority.
        clone.preserveSemanticIdentityFrom(node);
        clone.preserveParserSignatureEvidenceFrom(node);
        return clone;
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        if (type.startsWith("VAR_")) {
            return type.substring(4);
        }
        return type;
    }

    private static boolean isQuantifier(Opcode opcode) {
        return opcode == Opcode.FORALL || opcode == Opcode.EXISTS || opcode == Opcode.NO
                || opcode == Opcode.LONE || opcode == Opcode.ONE || opcode == Opcode.SUM
                || opcode == Opcode.COMPREHENSION;
    }

    private static boolean isQuantifierNode(EGraphNode node) {
        if (!isQuantifier(node.getOpcode())) {
            return false;
        }
        for (EGraphNode child : node.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                return true;
            }
        }
        return false;
    }

    private static int countFormulaQuantifierNodes(EGraphNode node) {
        if (node == null) {
            return 0;
        }
        int count = isQuantifierNode(node)
                && node.getOpcode() != Opcode.COMPREHENSION
                && node.getOpcode() != Opcode.SUM ? 1 : 0;
        for (EGraphNode child : node.getChildren()) {
            count += countFormulaQuantifierNodes(child);
        }
        return count;
    }

    private static boolean consumesMatrixNegation(Opcode opcode) {
        return opcode == Opcode.NO || opcode == Opcode.ONE || opcode == Opcode.LONE;
    }

    private static boolean childNegated(Opcode opcode, int childIndex, boolean negated) {
        switch (opcode) {
            case IMPLIES:
                return childIndex == 0 ? !negated : negated;
            case ITE:
                // Boolean ITEs were expanded before prenexing. A remaining ITE
                // is relational, so its condition and value branches are all
                // expression-positive; formula polarity must not be injected.
                return false;
            default:
                return negated;
        }
    }

    private static String childBindingPath(String parentPath, Opcode opcode, int childIndex, boolean childNegated) {
        StringBuilder path = new StringBuilder(parentPath);
        path.append('/').append(opcode.name().toLowerCase()).append('[').append(childIndex).append(']');
        if (childNegated) {
            path.append("/not");
        }
        return path.toString();
    }

    private static boolean isRelDecl(Opcode opcode) {
        return opcode == Opcode.DISJ || opcode == Opcode.VAR || opcode == Opcode.DISJVAR
                || opcode == Opcode.GENERICRELDECL;
    }

    private static boolean isDisj(Opcode opcode) {
        return opcode == Opcode.DISJ || opcode == Opcode.DISJVAR;
    }

    @LeanVerifiedRewrite({"R0-CORE-011", "R0-CORE-012"})
    private static Quantifier quantifierOf(Opcode opcode, boolean negated) {
        switch (opcode) {
            case FORALL:
                return negated ? Quantifier.SOME : Quantifier.ALL;
            case EXISTS:
                return negated ? Quantifier.ALL : Quantifier.SOME;
            case NO:
                return negated ? Quantifier.SOME : Quantifier.ALL;
            case LONE:
                return negated ? Quantifier.NOTLONE : Quantifier.LONE;
            case ONE:
                return negated ? Quantifier.NOTONE : Quantifier.ONE;
            case SUM:
                return Quantifier.SUM;
            case COMPREHENSION:
                return Quantifier.COMPREHENSION;
            default:
                return Quantifier.SOME;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static final class RelDeclResult {
        private final List<QuantiVar> quantiVars;
        private final Boolean emptyDomainValue;

        private RelDeclResult(List<QuantiVar> quantiVars, Boolean emptyDomainValue) {
            this.quantiVars = quantiVars;
            this.emptyDomainValue = emptyDomainValue;
        }
    }

    private final class PrenexSlotAllocator {
        private final Map<BindingSignature, Integer> nextSlots;
        private final Map<BindingCoordinate, QuantiVar> representatives;
        private Quantifier reusableQuantifier;
        private Map<BindingSignature, Integer> fallbackSlots;
        private boolean reuseOpen;

        private PrenexSlotAllocator() {
            this(new HashMap<>(), new HashMap<>(),
                    null, new HashMap<>(), false);
        }

        private PrenexSlotAllocator(
                Map<BindingSignature, Integer> nextSlots,
                Map<BindingCoordinate, QuantiVar> representatives,
                Quantifier reusableQuantifier,
                Map<BindingSignature, Integer> fallbackSlots,
                boolean reuseOpen) {
            this.nextSlots = nextSlots;
            this.representatives = representatives;
            this.reusableQuantifier = reusableQuantifier;
            this.fallbackSlots = fallbackSlots;
            this.reuseOpen = reuseOpen;
        }

        private PrenexSlotAllocator copy() {
            return new PrenexSlotAllocator(
                    new HashMap<>(nextSlots),
                    representatives,
                    reusableQuantifier,
                    new HashMap<>(fallbackSlots),
                    reuseOpen);
        }

        private PrenexSlotAllocator domainView() {
            PrenexSlotAllocator domain = copy();
            domain.reusableQuantifier = null;
            domain.fallbackSlots = new HashMap<>();
            domain.reuseOpen = false;
            return domain;
        }

        private PrenexSlotAllocator branchFrom(
                PrenexSlotAllocator base,
                Quantifier reusable) {
            PrenexSlotAllocator branch = copy();
            branch.reusableQuantifier = reusable;
            branch.fallbackSlots = new HashMap<>();
            for (Map.Entry<BindingSignature, Integer> entry : nextSlots.entrySet()) {
                if (entry.getKey().quantifier == reusable) {
                    branch.fallbackSlots.put(entry.getKey(), entry.getValue());
                }
            }
            branch.reuseOpen = true;
            branch.nextSlots.entrySet().removeIf(
                    entry -> entry.getKey().quantifier == reusable);
            for (Map.Entry<BindingSignature, Integer> entry : base.nextSlots.entrySet()) {
                if (entry.getKey().quantifier == reusable) {
                    branch.nextSlots.put(entry.getKey(), entry.getValue());
                }
            }
            return branch;
        }

        private void enterQuantifier(Quantifier quantifier) {
            if (reuseOpen && reusableQuantifier != quantifier) {
                closeReuse();
            }
        }

        private void enterStructure(Quantifier compatibleQuantifier) {
            if (reuseOpen && reusableQuantifier != compatibleQuantifier) {
                closeReuse();
            }
        }

        private void closeReuse() {
            if (!reuseOpen) {
                return;
            }
            for (Map.Entry<BindingSignature, Integer> entry : fallbackSlots.entrySet()) {
                nextSlots.merge(entry.getKey(), entry.getValue(), Math::max);
            }
            reuseOpen = false;
        }

        private void mergeBranch(
                PrenexSlotAllocator branch,
                Quantifier reusable) {
            for (Map.Entry<BindingSignature, Integer> entry : branch.nextSlots.entrySet()) {
                if (entry.getKey().quantifier == reusable) {
                    nextSlots.merge(entry.getKey(), entry.getValue(), Math::max);
                } else {
                    nextSlots.put(entry.getKey(), entry.getValue());
                }
            }
        }

        private QuantiVar bind(QuantiVar candidate) {
            BindingSignature signature = new BindingSignature(candidate);
            int ordinal = nextSlots.getOrDefault(signature, 0);
            nextSlots.put(signature, ordinal + 1);
            BindingCoordinate coordinate = new BindingCoordinate(signature, ordinal);
            QuantiVar representative = representatives.get(coordinate);
            if (representative != null) {
                representative.mergeExactAlloyType(candidate.getExactAlloyType());
                representative.addOriginalName(candidate.getOriginalName());
                return representative;
            }
            representatives.put(coordinate, candidate);
            matrixQuantiVars.add(candidate);
            return candidate;
        }
    }

    private static is.fivefivefive.ACGN.alloy.ExactAlloyType bindingTypeEvidence(
            EGraphNode variable,
            EGraphNode domain) {
        is.fivefivefive.ACGN.alloy.ExactAlloyType direct =
                variable.getExactAlloyType();
        if (usableBindingTypeEvidence(direct)) {
            return direct;
        }
        is.fivefivefive.ACGN.alloy.ExactAlloyType declared = domain == null
                ? null : domain.getExactAlloyType();
        return usableBindingTypeEvidence(declared) ? declared : null;
    }

    private static boolean usableBindingTypeEvidence(
            is.fivefivefive.ACGN.alloy.ExactAlloyType evidence) {
        if (evidence == null) {
            return false;
        }
        if (evidence.kind()
                == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.INT) {
            return true;
        }
        return evidence.kind()
                        == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                && evidence.relationArity() >= 1
                && !evidence.alternatives().isEmpty();
    }

    private static void inheritBindingTypeEvidence(
            EGraphNode occurrence,
            QuantiVar binding) {
        is.fivefivefive.ACGN.alloy.ExactAlloyType declared =
                binding.getExactAlloyType();
        if (declared == null) {
            return;
        }
        is.fivefivefive.ACGN.alloy.ExactAlloyType current =
                occurrence.getExactAlloyType();
        if (current == null || current.sameOccurrenceEvidenceAs(declared)) {
            occurrence.setExactAlloyType(declared);
            return;
        }
        if (current.equals(declared)
                && !current.hasParserAuthenticatedAncestry()
                && declared.hasParserAuthenticatedAncestry()) {
            occurrence.setExactAlloyType(declared);
            return;
        }
        throw new IllegalStateException(
                "A bound variable occurrence has incompatible declaration type evidence");
    }

    private static final class BindingSignature {
        private final Quantifier quantifier;
        private final Cardinality cardinality;
        private final int disjointnessClass;
        private final String type;
        private final String carrierType;
        private final String dependentRelationType;

        private BindingSignature(QuantiVar variable) {
            this.quantifier = variable.getQuantifier();
            this.cardinality = variable.getCardinality();
            this.disjointnessClass = variable.getDisjointnessClass();
            this.type = normalizeType(variable.getTypeName());
            this.carrierType = normalizeType(variable.getCarrierTypeName());
            is.fivefivefive.ACGN.alloy.ExactAlloyType exact =
                    variable.getExactAlloyType();
            this.dependentRelationType = exact != null
                            && exact.kind()
                                    == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                            && exact.relationArity() > 1
                    ? exact.stableString() : "";
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BindingSignature)) {
                return false;
            }
            BindingSignature signature = (BindingSignature) other;
            return quantifier == signature.quantifier
                    && cardinality == signature.cardinality
                    && disjointnessClass == signature.disjointnessClass
                    && type.equals(signature.type)
                    && carrierType.equals(signature.carrierType)
                    && dependentRelationType.equals(
                            signature.dependentRelationType);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    quantifier, cardinality, disjointnessClass, type,
                    carrierType, dependentRelationType);
        }
    }

    private static final class BindingCoordinate {
        private final BindingSignature signature;
        private final int ordinal;

        private BindingCoordinate(BindingSignature signature, int ordinal) {
            this.signature = signature;
            this.ordinal = ordinal;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BindingCoordinate)) {
                return false;
            }
            BindingCoordinate coordinate = (BindingCoordinate) other;
            return ordinal == coordinate.ordinal
                    && signature.equals(coordinate.signature);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(signature, ordinal);
        }
    }

    private static final class DomainDescriptor {
        private final EGraphNode domain;
        private final Cardinality cardinality;

        private DomainDescriptor(EGraphNode domain, Cardinality cardinality) {
            this.domain = domain;
            this.cardinality = cardinality == null ? Cardinality.SET : cardinality;
        }
    }
}
