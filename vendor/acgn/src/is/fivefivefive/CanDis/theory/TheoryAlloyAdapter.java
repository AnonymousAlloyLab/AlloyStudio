package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.CallMetadata;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.EClassRef;
import is.fivefivefive.CanDis.core.EGraphNode.FlexibleArityKind;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.NormalForm.TemporalOp;
import is.fivefivefive.CanDis.core.QuantiVar;
import is.fivefivefive.CanDis.theory.BagPortSchema;
import is.fivefivefive.CanDis.theory.AlloyLawRegistry;
import is.fivefivefive.CanDis.theory.ArityPolicy;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.BagPort;
import is.fivefivefive.CanDis.theory.BindBlockPort;
import is.fivefivefive.CanDis.theory.BindBlockPortSchema;
import is.fivefivefive.CanDis.theory.BinderAutomorphismCertificate;
import is.fivefivefive.CanDis.theory.BinderBlockDescriptor;
import is.fivefivefive.CanDis.theory.BinderCoordinateDescriptor;
import is.fivefivefive.CanDis.theory.BoundedFiniteUnfoldingOracle;
import is.fivefivefive.CanDis.theory.CanonicalShape;
import is.fivefivefive.CanDis.theory.CertificateOrigin;
import is.fivefivefive.CanDis.theory.CertifiedInsertionResult;
import is.fivefivefive.CanDis.theory.CertifiedFlatConstruction;
import is.fivefivefive.CanDis.theory.CertifiedContainerConstruction;
import is.fivefivefive.CanDis.theory.ContainerConstructionCertificate;
import is.fivefivefive.CanDis.theory.ConstructionSourceLedger;
import is.fivefivefive.CanDis.theory.CertifiedSemanticArtifact;
import is.fivefivefive.CanDis.theory.CertificateExportSession;
import is.fivefivefive.CanDis.theory.CertificateProvenance;
import is.fivefivefive.CanDis.theory.CertificateTraceSink;
import is.fivefivefive.CanDis.theory.CoherentWitnessFamily;
import is.fivefivefive.CanDis.theory.ContainerEmptiness;
import is.fivefivefive.CanDis.theory.ContainerLawCertificate;
import is.fivefivefive.CanDis.theory.ContainerLawDeclaration;
import is.fivefivefive.CanDis.theory.FiniteUnfoldingBounds;
import is.fivefivefive.CanDis.theory.FiniteUnfoldingTree;
import is.fivefivefive.CanDis.theory.FlatApplication;
import is.fivefivefive.CanDis.theory.FlatInput;
import is.fivefivefive.CanDis.theory.FlatLeaf;
import is.fivefivefive.CanDis.theory.FlatConstructionCertificate;
import is.fivefivefive.CanDis.theory.GraphStatus;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.InstantiatedOperator;
import is.fivefivefive.CanDis.theory.InvocationPortLeaf;
import is.fivefivefive.CanDis.theory.OnePort;
import is.fivefivefive.CanDis.theory.OnePortSchema;
import is.fivefivefive.CanDis.theory.NoOpCertificateTraceSink;
import is.fivefivefive.CanDis.theory.OperatorDeclaration;
import is.fivefivefive.CanDis.theory.PortPath;
import is.fivefivefive.CanDis.theory.PortSchema;
import is.fivefivefive.CanDis.theory.PortValue;
import is.fivefivefive.CanDis.theory.SeqPortSchema;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.SetPortSchema;
import is.fivefivefive.CanDis.theory.SlotAlphabet;
import is.fivefivefive.CanDis.theory.StructuralKey;
import is.fivefivefive.CanDis.theory.TypedEClassRecord;
import is.fivefivefive.CanDis.theory.TypedENode;
import is.fivefivefive.CanDis.theory.TypedEmbedding;
import is.fivefivefive.CanDis.theory.TypedInvocation;
import is.fivefivefive.CanDis.theory.TypedPermutation;
import is.fivefivefive.CanDis.theory.TypedRenaming;
import is.fivefivefive.CanDis.theory.RecordingCertificateTraceSink;
import is.fivefivefive.CanDis.theory.TypedSlot;
import is.fivefivefive.CanDis.theory.TypedSlotContext;
import is.fivefivefive.CanDis.theory.TypedSlottedPortEGraph;

/** Converts normalized Alloy temporal phases into the exact typed graph. */
public final class TheoryAlloyAdapter {
    public static final String ADAPTER_VERSION = SemanticProfile.PRODUCTION_ADAPTER_VERSION;
    public static final String SIGNATURE_VERSION = SemanticProfile.PRODUCTION_SIGNATURE_VERSION;
    public static final String INVARIANT_MODE = "strict-every-transition";
    private TheoryAlloyAdapter() {
    }

    public static Result adapt(List<NormalForm> normalForms) {
        return adapt(normalForms, SemanticProfile.alloyOverflowForbidding());
    }

    public static Result adapt(
            List<NormalForm> normalForms,
            SemanticProfile semanticProfile) {
        return new Builder(
                normalForms,
                semanticProfile,
                NoOpCertificateTraceSink.instance(),
                null).build();
    }

    /** Exact proof-retaining path used only by Phase J export. */
    public static Result adaptForVerification(
            List<NormalForm> normalForms,
            RecordingCertificateTraceSink sink,
            CertificateProvenance provenance) {
        return adaptForVerification(
                normalForms,
                SemanticProfile.alloyOverflowForbidding(),
                sink,
                provenance);
    }

    /** Exact proof-retaining path used only by Phase J export. */
    public static Result adaptForVerification(
            List<NormalForm> normalForms,
            SemanticProfile semanticProfile,
            RecordingCertificateTraceSink sink,
            CertificateProvenance provenance) {
        return new Builder(
                normalForms,
                semanticProfile,
                Objects.requireNonNull(sink, "sink"),
                Objects.requireNonNull(provenance, "provenance")).build();
    }

    /** Immutable bridge tying one mutable Fast Rewrite source occurrence to evidence. */
    public static final class DependentChainSourceBinding {
        private final DependentChainCertificate certificate;
        private final long sourceOccurrenceLineage;
        private final String sourceOccurrencePath;
        private final StructuralKey sourceOccurrenceCommitment;
        private final StructuralKey boundRepairOccurrenceCommitment;
        private final String transferContentCommitment;

        private DependentChainSourceBinding(
                EGraphNode source,
                String sourceOccurrencePath,
                DependentChainCertificate certificate) {
            this.certificate = Objects.requireNonNull(certificate, "certificate");
            this.sourceOccurrenceLineage = Objects.requireNonNull(
                    source, "source").getSourceOccurrenceLineage();
            if (sourceOccurrenceLineage <= 0L) {
                throw new IllegalArgumentException(
                        "A dependent source occurrence requires positive lineage");
            }
            this.sourceOccurrencePath = requireSourceOccurrencePath(
                    sourceOccurrencePath);
            this.sourceOccurrenceCommitment = sourceOccurrenceCommitment(
                    source,
                    this.sourceOccurrencePath,
                    certificate.source().structuralKey());
            if (!this.sourceOccurrenceCommitment.equals(
                    certificate.sourceOccurrenceCommitment())) {
                throw new IllegalArgumentException(
                        "A dependent certificate names another source occurrence");
            }
            this.boundRepairOccurrenceCommitment = sourceOccurrenceCommitment;
            this.transferContentCommitment =
                    source.dependentChainTransferContentCommitment();
        }

        private DependentChainSourceBinding(
                EGraphNode repairSource,
                DependentChainSourceBinding certifiedSource) {
            this.certificate = certifiedSource.certificate;
            this.sourceOccurrenceLineage = certifiedSource.sourceOccurrenceLineage;
            this.sourceOccurrencePath = certifiedSource.sourceOccurrencePath;
            this.sourceOccurrenceCommitment =
                    certifiedSource.sourceOccurrenceCommitment;
            this.transferContentCommitment =
                    certifiedSource.transferContentCommitment;
            requireLineage(repairSource);
            String repairTransferContent =
                    repairSource.dependentChainTransferContentCommitment();
            if (!transferContentCommitment.equals(repairTransferContent)) {
                throw new IllegalArgumentException(
                        "A dependent source changed outside its certified ACI operands");
            }
            this.boundRepairOccurrenceCommitment = sourceOccurrenceCommitment(
                    repairSource,
                    sourceOccurrencePath,
                    certificate.source().structuralKey());
        }

        private static String requireSourceOccurrencePath(String path) {
            Objects.requireNonNull(path, "sourceOccurrencePath");
            if (path.isBlank()) {
                throw new IllegalArgumentException(
                        "A dependent source occurrence path must not be blank");
            }
            return path;
        }

        static StructuralKey sourceOccurrenceCommitment(
                EGraphNode source,
                String sourceOccurrencePath,
                StructuralKey typedSourceKey) {
            return StructuralKey.of(
                    "alloy-dependent-chain-source-occurrence-v1",
                    List.of(requireSourceOccurrencePath(sourceOccurrencePath)),
                    List.of(
                            StructuralKey.branch(
                                    "alloy-dependent-chain-typed-source-v1",
                                    List.of(Objects.requireNonNull(
                                            typedSourceKey, "typedSourceKey"))),
                            StructuralKey.leaf(
                                    "alloy-dependent-chain-source-content-v1",
                                    Objects.requireNonNull(source, "source")
                                            .dependentChainSourceContentCommitment())));
        }

        private DependentChainSourceBinding transferTo(EGraphNode source) {
            requireLineage(source);
            return new DependentChainSourceBinding(source, this);
        }

        public void requireMatches(EGraphNode source) {
            requireLineage(source);
            StructuralKey current = sourceOccurrenceCommitment(
                    source,
                    sourceOccurrencePath,
                    certificate.source().structuralKey());
            if (!boundRepairOccurrenceCommitment.equals(current)
                    || !transferContentCommitment.equals(
                            source.dependentChainTransferContentCommitment())) {
                throw new IllegalStateException(
                        "A certified dependent source occurrence changed after adaptation");
            }
        }

        private void requireLineage(EGraphNode source) {
            Objects.requireNonNull(source, "source");
            if (source.getSourceOccurrenceLineage() != sourceOccurrenceLineage) {
                throw new IllegalStateException(
                        "A dependent certificate was attached to another source lineage");
            }
        }

        public DependentChainCertificate certificate() {
            return certificate;
        }

        public String sourceOccurrencePath() {
            return sourceOccurrencePath;
        }

        public StructuralKey sourceOccurrenceCommitment() {
            return sourceOccurrenceCommitment;
        }

        private boolean sameTransferPlanAs(
                DependentChainSourceBinding other) {
            return other != null
                    && sourceOccurrenceLineage
                            == other.sourceOccurrenceLineage
                    && transferContentCommitment.equals(
                            other.transferContentCommitment)
                    && certificate.source().structuralKey().equals(
                            other.certificate.source().structuralKey())
                    && certificate.target().structuralKey().equals(
                            other.certificate.target().structuralKey())
                    && certificate.theoryIndex().equals(
                            other.certificate.theoryIndex());
        }
    }

    /**
     * Exact Set-normalization fibers transferred from one certified source
     * occurrence to its repaired occurrence by preserved lineage.
     */
    public static final class CertifiedSetOperandPartition {
        private final long sourceOccurrenceLineage;
        private final Opcode sourceOpcode;
        private final int inputArity;
        private final List<Long> inputSourceLineages;
        private final List<List<Integer>> outputFibers;
        private final StructuralKey traceKey;

        private CertifiedSetOperandPartition(
                EGraphNode source,
                ContainerApplicationTrace trace) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(trace, "trace");
            if (!(trace.schema() instanceof SetPortSchema)) {
                throw new IllegalArgumentException(
                        "Only a certified Set trace defines an idempotent partition");
            }
            sourceOccurrenceLineage = source.getCertificationOccurrenceLineage();
            if (sourceOccurrenceLineage <= 0L) {
                throw new IllegalArgumentException(
                        "A certified Set partition requires positive source lineage");
            }
            sourceOpcode = source.getOpcode();
            inputArity = trace.inputOccurrences().size();
            List<EGraphNode> sourceInputs = new ArrayList<>();
            collectFlatInputs(source, source, sourceInputs,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            if (sourceInputs.size() != inputArity) {
                throw new IllegalStateException(
                        "A certified Set trace and its source have different flattened arity");
            }
            List<Long> sourceLineages = new ArrayList<>(sourceInputs.size());
            for (EGraphNode input : sourceInputs) {
                long lineage = input.getCertificationOccurrenceLineage();
                if (lineage <= 0L) {
                    throw new IllegalStateException(
                            "A certified Set input requires positive source lineage");
                }
                sourceLineages.add(lineage);
            }
            inputSourceLineages = Collections.unmodifiableList(sourceLineages);
            outputFibers = trace.outputFibers();
            traceKey = trace.structuralKey();
        }

        private static void collectFlatInputs(
                EGraphNode root,
                EGraphNode current,
                List<EGraphNode> inputs,
                Set<EGraphNode> active) {
            if (current != root && !root.sameFlatOperatorInstance(current)) {
                inputs.add(current);
                return;
            }
            if (!active.add(current)) {
                throw new IllegalStateException(
                        "A certified Set source contains a recursive flat occurrence");
            }
            try {
                for (EGraphNode child : current.getChildren()) {
                    if (root.sameFlatOperatorInstance(child)) {
                        collectFlatInputs(root, child, inputs, active);
                    } else {
                        inputs.add(child);
                    }
                }
            } finally {
                active.remove(current);
            }
        }

        private boolean samePartition(CertifiedSetOperandPartition other) {
            return other != null
                    && sourceOccurrenceLineage == other.sourceOccurrenceLineage
                    && sourceOpcode == other.sourceOpcode
                    && inputArity == other.inputArity
                    && inputSourceLineages.equals(other.inputSourceLineages)
                    && outputFibers.equals(other.outputFibers);
        }

        private boolean deduplicated() {
            return outputFibers.size() < inputArity;
        }

        public List<List<Integer>> inputFibers(EGraphNode repairedSource) {
            Objects.requireNonNull(repairedSource, "repairedSource");
            if (repairedSource.getCertificationOccurrenceLineage()
                            != sourceOccurrenceLineage
                    || repairedSource.getOpcode() != sourceOpcode
                    || !repairedSource.isSetFlexibleArity()
                    || repairedSource.getChildren().size() != inputArity) {
                throw new IllegalStateException(
                        "A certified Set partition was attached to another repair occurrence");
            }
            for (List<Integer> fiber : outputFibers) {
                if (fiber.isEmpty()) {
                    throw new IllegalStateException(
                            "A certified Set normalization fiber is empty");
                }
            }
            Map<Long, ArrayDeque<Integer>> repairedByLineage = new LinkedHashMap<>();
            List<EGraphNode> repairedInputs = repairedSource.getChildren();
            for (int index = 0; index < repairedInputs.size(); index++) {
                long lineage = repairedInputs.get(index)
                        .getCertificationOccurrenceLineage();
                if (lineage <= 0L) {
                    throw new IllegalStateException(
                            "A repaired Set input requires positive source lineage");
                }
                repairedByLineage.computeIfAbsent(
                        lineage, ignored -> new ArrayDeque<>()).addLast(index);
            }
            int[] repairedIndex = new int[inputArity];
            for (int sourceIndex = 0; sourceIndex < inputArity; sourceIndex++) {
                long lineage = inputSourceLineages.get(sourceIndex);
                ArrayDeque<Integer> candidates = repairedByLineage.get(lineage);
                if (candidates == null || candidates.isEmpty()) {
                    throw new IllegalStateException(
                            "A repaired Set input cannot be matched to its certified source lineage"
                                    + " (sourceIndex=" + sourceIndex
                                    + ", missingLineage=" + lineage
                                    + ", certifiedLineages=" + inputSourceLineages
                                    + ", repairedLineages="
                                    + repairedInputs.stream()
                                            .map(EGraphNode::getCertificationOccurrenceLineage)
                                            .collect(java.util.stream.Collectors.toList())
                                    + ")");
                }
                repairedIndex[sourceIndex] = candidates.removeFirst();
            }
            for (ArrayDeque<Integer> unmatched : repairedByLineage.values()) {
                if (!unmatched.isEmpty()) {
                    throw new IllegalStateException(
                            "A repaired Set contains an unmatched source lineage");
                }
            }
            List<List<Integer>> remapped = new ArrayList<>(outputFibers.size());
            for (List<Integer> fiber : outputFibers) {
                List<Integer> repairedFiber = new ArrayList<>(fiber.size());
                for (int sourceIndex : fiber) {
                    repairedFiber.add(repairedIndex[sourceIndex]);
                }
                Collections.sort(repairedFiber);
                remapped.add(Collections.unmodifiableList(repairedFiber));
            }
            return Collections.unmodifiableList(remapped);
        }

        public StructuralKey traceKey() {
            return traceKey;
        }
    }

    /** Certified import of one owner-local coordinate into a temporal phase. */
    public static final class PhaseLocalBindingCertificate {
        private final NormalForm.PhaseLocalBindingImport source;
        private final int ownerPhase;
        private final BinderBlockDescriptor ownerDescriptor;
        private final int coordinate;
        private final String ownerContext;

        private PhaseLocalBindingCertificate(
                NormalForm.PhaseLocalBindingImport source,
                int ownerPhase,
                BinderBlockDescriptor ownerDescriptor,
                int coordinate) {
            this.source = Objects.requireNonNull(source, "phase-local source import");
            if (ownerPhase < 0) {
                throw new IllegalArgumentException(
                        "A phase-local certificate requires its owner phase");
            }
            this.ownerPhase = ownerPhase;
            this.ownerDescriptor = Objects.requireNonNull(
                    ownerDescriptor, "phase-local owner descriptor");
            if (coordinate < 0 || coordinate >= ownerDescriptor.coordinates().size()) {
                throw new IllegalArgumentException(
                        "A phase-local certificate coordinate is outside its owner descriptor");
            }
            this.coordinate = coordinate;
            this.ownerContext = source.ownerPhasePath() + "|" + source.binderContext();
        }

        public NormalForm.PhaseLocalBindingImport source() {
            return source;
        }

        public int ownerPhase() {
            return ownerPhase;
        }

        public BinderBlockDescriptor ownerDescriptor() {
            return ownerDescriptor;
        }

        public int coordinate() {
            return coordinate;
        }

        public String ownerContext() {
            return ownerContext;
        }
    }

    public static final class Result {
        private final CertifiedSemanticArtifact semanticArtifact;
        private final StructuralKey canonicalKey;
        private final List<NormalForm> repairProjectionSources;
        private final List<BinderBlockDescriptor> phaseBinderDescriptors;
        private final List<List<Integer>> phaseSourceCoordinates;
        private final Map<EGraphNode, BinderBlockDescriptor> localBinderDescriptors;
        private final Map<EGraphNode, Map<String, Integer>> localBinderSourceCoordinates;
        private final Map<NormalForm, List<PhaseLocalBindingCertificate>>
                phaseLocalBindingCertificates;
        private final Map<EGraphNode, DependentChainCertificate>
                dependentChainSourceCertificates;
        private final Map<EGraphNode, DependentChainSourceBinding>
                dependentChainSourceBindings;
        private final Map<EGraphNode, CertifiedSetOperandPartition>
                certifiedSetOperandPartitions;
        private final long eclasses;
        private final long enodes;
        private final long slots;
        private final long rebuilds;
        private final long estimatedBytes;
        private final long constructionNanos;
        private final long unfoldingNanos;
        private final long observationNanos;
        private final CertificateExportSession exportSession;

        private Result(
                CertifiedSemanticArtifact semanticArtifact,
                StructuralKey canonicalKey,
                List<? extends NormalForm> repairProjectionSources,
                List<? extends BinderBlockDescriptor> phaseBinderDescriptors,
                List<? extends List<Integer>> phaseSourceCoordinates,
                Map<EGraphNode, BinderBlockDescriptor> localBinderDescriptors,
                Map<EGraphNode, Map<String, Integer>> localBinderSourceCoordinates,
                Map<NormalForm, List<PhaseLocalBindingCertificate>>
                        phaseLocalBindingCertificates,
                Map<EGraphNode, DependentChainSourceBinding>
                        dependentChainSourceBindings,
                Map<EGraphNode, CertifiedSetOperandPartition>
                        certifiedSetOperandPartitions,
                long eclasses,
                long enodes,
                long slots,
                long rebuilds,
                long estimatedBytes,
                long constructionNanos,
                long unfoldingNanos,
                long observationNanos,
                CertificateExportSession exportSession) {
            this.semanticArtifact = Objects.requireNonNull(
                    semanticArtifact, "semanticArtifact");
            this.canonicalKey = canonicalKey;
            this.repairProjectionSources = Collections.unmodifiableList(
                    new ArrayList<>(repairProjectionSources));
            for (NormalForm source : this.repairProjectionSources) {
                if (source == null || !source.isFrozenForCertification()) {
                    throw new IllegalArgumentException(
                            "A repair projection source must be frozen before certification");
                }
            }
            this.phaseBinderDescriptors = Collections.unmodifiableList(
                    new ArrayList<>(phaseBinderDescriptors));
            List<List<Integer>> coordinateCopies = new ArrayList<>(
                    phaseSourceCoordinates.size());
            for (List<Integer> coordinates : phaseSourceCoordinates) {
                coordinateCopies.add(Collections.unmodifiableList(
                        new ArrayList<>(coordinates)));
            }
            this.phaseSourceCoordinates = Collections.unmodifiableList(coordinateCopies);
            this.localBinderDescriptors = Collections.unmodifiableMap(
                    new IdentityHashMap<>(localBinderDescriptors));
            IdentityHashMap<EGraphNode, Map<String, Integer>> localCoordinateCopies =
                    new IdentityHashMap<>();
            for (Map.Entry<EGraphNode, Map<String, Integer>> entry
                    : localBinderSourceCoordinates.entrySet()) {
                localCoordinateCopies.put(
                        entry.getKey(),
                        Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
            }
            this.localBinderSourceCoordinates = Collections.unmodifiableMap(
                    localCoordinateCopies);
            IdentityHashMap<NormalForm, List<PhaseLocalBindingCertificate>>
                    phaseLocalCopies = new IdentityHashMap<>();
            for (Map.Entry<NormalForm, List<PhaseLocalBindingCertificate>> entry
                    : phaseLocalBindingCertificates.entrySet()) {
                phaseLocalCopies.put(
                        entry.getKey(),
                        Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
            this.phaseLocalBindingCertificates = Collections.unmodifiableMap(
                    phaseLocalCopies);
            IdentityHashMap<EGraphNode, DependentChainSourceBinding> bindingCopies =
                    new IdentityHashMap<>();
            IdentityHashMap<EGraphNode, DependentChainCertificate> certificateCopies =
                    new IdentityHashMap<>();
            for (Map.Entry<EGraphNode, DependentChainSourceBinding> entry
                    : dependentChainSourceBindings.entrySet()) {
                EGraphNode source = Objects.requireNonNull(
                        entry.getKey(), "dependent source occurrence");
                DependentChainSourceBinding binding = Objects.requireNonNull(
                        entry.getValue(), "dependent source binding");
                binding.requireMatches(source);
                bindingCopies.put(source, binding);
                certificateCopies.put(source, binding.certificate());
            }
            this.dependentChainSourceBindings = Collections.unmodifiableMap(bindingCopies);
            this.dependentChainSourceCertificates = Collections.unmodifiableMap(
                    certificateCopies);
            IdentityHashMap<EGraphNode, CertifiedSetOperandPartition> partitionCopies =
                    new IdentityHashMap<>();
            for (Map.Entry<EGraphNode, CertifiedSetOperandPartition> entry
                    : certifiedSetOperandPartitions.entrySet()) {
                EGraphNode source = Objects.requireNonNull(
                        entry.getKey(), "repaired Set occurrence");
                CertifiedSetOperandPartition partition = Objects.requireNonNull(
                        entry.getValue(), "certified Set partition");
                partition.inputFibers(source);
                partitionCopies.put(source, partition);
            }
            this.certifiedSetOperandPartitions = Collections.unmodifiableMap(
                    partitionCopies);
            this.eclasses = eclasses;
            this.enodes = enodes;
            this.slots = slots;
            this.rebuilds = rebuilds;
            this.estimatedBytes = estimatedBytes;
            this.constructionNanos = constructionNanos;
            this.unfoldingNanos = unfoldingNanos;
            this.observationNanos = observationNanos;
            this.exportSession = exportSession;
        }

        public StructuralKey canonicalKey() {
            return canonicalKey;
        }

        public CertifiedSemanticArtifact semanticArtifact() {
            return semanticArtifact;
        }

        /** Rejects projection over any source other than this result's frozen input. */
        public void requireRepairProjectionSources(List<NormalForm> sources) {
            Objects.requireNonNull(sources, "repair projection sources");
            if (sources.size() != repairProjectionSources.size()) {
                throw new IllegalArgumentException(
                        "Repair projection source count differs from certified input");
            }
            for (int index = 0; index < sources.size(); index++) {
                NormalForm source = sources.get(index);
                if (source != repairProjectionSources.get(index)
                        || !source.isFrozenForCertification()) {
                    throw new IllegalArgumentException(
                            "Repair projection source identity differs at phase " + index);
                }
            }
        }

        /** Certified binder carrier for each repaired normal-form phase, in phase order. */
        public List<BinderBlockDescriptor> phaseBinderDescriptors() {
            return phaseBinderDescriptors;
        }

        /** Source NormalForm binding index to certified descriptor coordinate. */
        public List<List<Integer>> phaseSourceCoordinates() {
            return phaseSourceCoordinates;
        }

        /** Certified descriptors for source binders retained inside a phase matrix. */
        public Map<EGraphNode, BinderBlockDescriptor> localBinderDescriptors() {
            return localBinderDescriptors;
        }

        /** Source local-variable name to certified descriptor coordinate. */
        public Map<EGraphNode, Map<String, Integer>> localBinderSourceCoordinates() {
            return localBinderSourceCoordinates;
        }

        /** Exact owner-coordinate proofs for local binders crossing a temporal edge. */
        public Map<NormalForm, List<PhaseLocalBindingCertificate>>
                phaseLocalBindingCertificates() {
            return phaseLocalBindingCertificates;
        }

        /** Source occurrence to the exact certificate authorizing its ordered flattening. */
        public Map<EGraphNode, DependentChainCertificate>
                dependentChainSourceCertificates() {
            return dependentChainSourceCertificates;
        }

        /** Exact occurrence bindings consumed by the repair projection. */
        public Map<EGraphNode, DependentChainSourceBinding>
                dependentChainSourceBindings() {
            return dependentChainSourceBindings;
        }

        /** Exact idempotent fibers for repaired ACI Set occurrences. */
        public Map<EGraphNode, CertifiedSetOperandPartition>
                certifiedSetOperandPartitions() {
            return certifiedSetOperandPartitions;
        }

        public long eclasses() {
            return eclasses;
        }

        public long enodes() {
            return enodes;
        }

        public long slots() {
            return slots;
        }

        public long rebuilds() {
            return rebuilds;
        }

        public long estimatedBytes() {
            return estimatedBytes;
        }

        public long constructionNanos() {
            return constructionNanos;
        }

        public long unfoldingNanos() {
            return unfoldingNanos;
        }

        public long observationNanos() {
            return observationNanos;
        }

        public CertificateExportSession certificateExportSession() {
            if (exportSession == null) {
                throw new IllegalStateException(
                        "Ordinary adaptation does not retain a certificate export session");
            }
            return exportSession;
        }

        public boolean retainsCertificateExportSession() {
            return exportSession != null;
        }
    }

    private static final class Builder {
        private final List<NormalForm> normalForms;
        private final Set<NormalForm> declaredForms;
        private final Set<NormalForm> builtForms = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<NormalForm, BinderBlockDescriptor> phaseBinderDescriptors =
                new IdentityHashMap<>();
        private final Map<NormalForm, List<Integer>> phaseSourceCoordinates =
                new IdentityHashMap<>();
        private final Map<EGraphNode, BinderBlockDescriptor> localBinderDescriptors =
                new IdentityHashMap<>();
        private final Map<EGraphNode, Map<String, Integer>> localBinderSourceCoordinates =
                new IdentityHashMap<>();
        private final Map<NormalForm, List<PhaseLocalBindingCertificate>>
                phaseLocalBindingCertificates = new IdentityHashMap<>();
        private final IdentityHashMap<NormalForm, Integer> phaseIndices =
                new IdentityHashMap<>();
        private final ArrayDeque<NormalForm> activePhases = new ArrayDeque<>();
        private final ArrayDeque<LocalBinderFrame> activeLocalBinders = new ArrayDeque<>();
        private final TypedSlottedPortEGraph graph;
        private final RecordingCertificateTraceSink recordingSink;
        private final CertificateProvenance provenance;
        private final SemanticProfile semanticProfile;
        private final Map<InvocationKey, TypedInvocation> memo = new HashMap<>();
        private final Map<RelationalCoercionKey, TypedInvocation> relationalCoercions =
                new HashMap<>();
        private final Map<String, List<ContainerLawDeclaration>> certifiedContainerLaws =
                new LinkedHashMap<>();
        private final List<FlatConstructionCertificate> flatConstructions =
                new ArrayList<>();
        private final List<ContainerConstructionCertificate> containerConstructions =
                new ArrayList<>();
        private final List<DependentChainCertificate> dependentChainConstructions =
                new ArrayList<>();
        private final List<CallOccurrenceCertificate> callOccurrenceCertificates =
                new ArrayList<>();
        private final Map<Long, CallOccurrenceCertificate> callOccurrencesById =
                new LinkedHashMap<>();
        private final Map<EGraphNode, DependentChainSourceBinding>
                dependentChainSourceBindings = new LinkedHashMap<>();
        private final Map<Long, CertifiedSetOperandPartition>
                certifiedSetPartitionsByLineage = new LinkedHashMap<>();
        private final Set<Long> nonuniformSetPartitionLineages = new HashSet<>();
        private final Map<EGraphNode, CertifiedSetOperandPartition>
                repairedSetOperandPartitions = new IdentityHashMap<>();
        private final Map<EGraphNode, String> sourceOccurrencePaths;
        private final ConstructionSourceLedger.Builder constructionSources;
        private final Set<InvocationKey> active = new HashSet<>();
        private long rebuilds;

        private Builder(
                List<NormalForm> normalForms,
                SemanticProfile semanticProfile,
                CertificateTraceSink traceSink,
                CertificateProvenance provenance) {
            Objects.requireNonNull(normalForms, "normalForms");
            this.graph = new TypedSlottedPortEGraph(
                    Objects.requireNonNull(semanticProfile, "semanticProfile"),
                    Objects.requireNonNull(traceSink, "traceSink"));
            this.recordingSink = traceSink instanceof RecordingCertificateTraceSink
                    ? (RecordingCertificateTraceSink) traceSink : null;
            this.provenance = provenance;
            this.semanticProfile = semanticProfile;
            this.constructionSources = ConstructionSourceLedger.builder(semanticProfile);
            this.normalForms = Collections.unmodifiableList(new ArrayList<>(normalForms));
            if (!this.normalForms.isEmpty()) {
                this.normalForms.get(0).requireAdmittedTemporalTree();
            }
            for (NormalForm normalForm : this.normalForms) {
                Objects.requireNonNull(normalForm, "normal form")
                        .freezeForCertification();
            }
            requireProfileConsistency(this.normalForms, this.semanticProfile);
            requirePristineCertificationSources(this.normalForms);
            this.sourceOccurrencePaths = indexSourceOccurrencePaths(this.normalForms);
            this.declaredForms = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int index = 0; index < this.normalForms.size(); index++) {
                NormalForm form = this.normalForms.get(index);
                this.declaredForms.add(form);
                if (phaseIndices.put(form, index) != null) {
                    throw new IllegalArgumentException(
                            "One temporal phase occurs twice in the adapter input");
                }
            }
            certifySourceContainerLaws();
        }

        private static Map<EGraphNode, String> indexSourceOccurrencePaths(
                List<NormalForm> normalForms) {
            IdentityHashMap<EGraphNode, String> paths = new IdentityHashMap<>();
            for (int phase = 0; phase < normalForms.size(); phase++) {
                EGraphNode root = normalForms.get(phase).getCertificationMatrixEGraph();
                if (root != null) {
                    indexSourceOccurrencePaths(
                            root,
                            "phase/" + phase + "/matrix",
                            paths,
                            Collections.newSetFromMap(new IdentityHashMap<>()));
                }
            }
            return Collections.unmodifiableMap(paths);
        }

        private static void indexSourceOccurrencePaths(
                EGraphNode node,
                String path,
                IdentityHashMap<EGraphNode, String> paths,
                Set<EGraphNode> active) {
            if (!active.add(node)) {
                throw new IllegalStateException(
                        "A certification source contains a recursive occurrence");
            }
            try {
                String prior = paths.putIfAbsent(node, path);
                if (prior != null) {
                    if (!prior.equals(path)) {
                        throw new IllegalStateException(
                                "One certification node represents two source occurrences: "
                                        + prior + " and " + path);
                    }
                    return;
                }
                List<EGraphNode> children = node.getChildren();
                for (int index = 0; index < children.size(); index++) {
                    indexSourceOccurrencePaths(
                            children.get(index),
                            path + "/child/" + index,
                            paths,
                            active);
                }
            } finally {
                active.remove(node);
            }
        }

        private static void requirePristineCertificationSources(
                List<NormalForm> normalForms) {
            Set<EGraphNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
            for (NormalForm normalForm : normalForms) {
                EGraphNode root = normalForm.getCertificationMatrixEGraph();
                if (root != null) {
                    pending.addLast(root);
                }
            }
            while (!pending.isEmpty()) {
                EGraphNode node = pending.removeFirst();
                if (!visited.add(node)) {
                    continue;
                }
                node.requirePristineCertificationSource();
                pending.addAll(node.getChildren());
            }
        }

        private void certifySourceContainerLaws() {
            Set<EGraphNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (NormalForm normalForm : normalForms) {
                certifySourceContainerLaws(
                        normalForm.getCertificationMatrixEGraph(), visited);
            }
        }

        private void certifySourceContainerLaws(
                EGraphNode node,
                Set<EGraphNode> visited) {
            if (node == null || !visited.add(node)) {
                return;
            }
            if (node.hasFlatLicense()) {
                PortSchema schema = containerSchema(
                        node.getFlexibleArityKind(),
                        new OnePortSchema(outputType(node)));
                String operator = semanticHead(node);
                recordContainerLaws(operator, certifiedLaws(
                        schema,
                        semanticProfile,
                        node.getOpcode(),
                        operator,
                        outputType(node),
                        true));
            }
            for (EGraphNode child : node.getChildren()) {
                certifySourceContainerLaws(child, visited);
            }
        }

        private Result build() {
            long phaseStarted = System.nanoTime();
            TypedInvocation root;
            if (normalForms.isEmpty()) {
                root = insert(constantNode("empty-normal-form", GraphType.BOOL, TypedSlotContext.empty()));
            } else {
                NormalForm rootForm = normalForms.get(0);
                Map<String, TypedSlot> bindings = new LinkedHashMap<>();
                TypedSlotContext context = addParameters(
                        TypedSlotContext.empty(), bindings, rootForm.getParams());
                root = buildPhase(rootForm, context, bindings);
                if (!builtForms.containsAll(declaredForms)) {
                    throw new IllegalStateException(
                            "The temporal normal-form list contains a phase unreachable from its root");
                }
            }
            mirrorCertifiedSourcePlansToRepairMatrices();
            mirrorCertifiedSetPartitionsToRepairMatrices();
            certifyRepairContainerLaws();
            long constructionNanos = System.nanoTime() - phaseStarted;
            phaseStarted = System.nanoTime();
            ensureQuiescent();
            graph.checkInvariants();
            CoherentWitnessFamily family = graph.coherentWitnessFamily();
            int depth = Math.max(1, graph.classes().size() + 1);
            BoundedFiniteUnfoldingOracle oracle = graph.finiteUnfoldingOracle(
                    family, new FiniteUnfoldingBounds(depth, 64));
            List<FiniteUnfoldingTree> unfoldings = oracle.enumerate(root);
            long unfoldingNanos = System.nanoTime() - phaseStarted;
            phaseStarted = System.nanoTime();
            if (unfoldings.isEmpty()) {
                throw new IllegalStateException(
                        "The acyclic Alloy adapter produced no complete finite unfolding");
            }
            NavigableSet<StructuralKey> normalized = new TreeSet<>();
            for (FiniteUnfoldingTree tree : unfoldings) {
                normalized.add(tree.normalizedTermKey());
            }
            StructuralKey key = StructuralKey.branch(
                    "canonical-alloy-form", new ArrayList<>(normalized));
            long observationNanos = System.nanoTime() - phaseStarted;

            long enodes = 0;
            long slots = 0;
            for (TypedEClassRecord record : graph.classes().values()) {
                enodes += record.shapeWitnesses().size();
                slots += record.exposedSlots().size();
            }
            long eclasses = graph.classes().size();
            long estimatedBytes = 64L * eclasses + 112L * enodes + 32L * slots;
            CertifiedSemanticArtifact semanticArtifact = new CertifiedSemanticArtifact(
                    root,
                    graph.classes(),
                    family,
                    unfoldings,
                    certifiedContainerLaws,
                    flatConstructions,
                    containerConstructions,
                    dependentChainConstructions,
                    callOccurrenceCertificates,
                    constructionSources.build(),
                    semanticProfile);
            CertificateExportSession exportSession = recordingSink == null
                    ? null
                    : new CertificateExportSession(
                            recordingSink,
                            graph,
                            semanticArtifact,
                            key,
                            certifiedContainerLaws,
                            provenance,
                            ADAPTER_VERSION + ";"
                                    + SIGNATURE_VERSION + ";"
                                    + graph.canonicalizerVersion() + ";"
                                    + graph.leaderKernelVersion() + ";"
                                    + graph.certificateVersion() + ";"
                                    + graph.rebuildVersion());
            List<BinderBlockDescriptor> orderedPhaseDescriptors = new ArrayList<>(normalForms.size());
            List<List<Integer>> orderedSourceCoordinates = new ArrayList<>(normalForms.size());
            for (NormalForm normalForm : normalForms) {
                BinderBlockDescriptor descriptor = phaseBinderDescriptors.get(normalForm);
                List<Integer> sourceCoordinates = phaseSourceCoordinates.get(normalForm);
                if (descriptor == null || sourceCoordinates == null) {
                    throw new IllegalStateException(
                            "A normalized temporal phase has no certified binder plan");
                }
                orderedPhaseDescriptors.add(descriptor);
                orderedSourceCoordinates.add(sourceCoordinates);
            }
            return new Result(
                    semanticArtifact,
                    key,
                    normalForms,
                    orderedPhaseDescriptors,
                    orderedSourceCoordinates,
                    localBinderDescriptors,
                    localBinderSourceCoordinates,
                    phaseLocalBindingCertificates,
                    dependentChainSourceBindings,
                    repairedSetOperandPartitions,
                    eclasses,
                    enodes,
                    slots,
                    rebuilds,
                    estimatedBytes,
                    constructionNanos,
                    unfoldingNanos,
                    observationNanos,
                    exportSession);
        }

        /**
         * The metric projects the frozen repaired matrix, while graph construction
         * starts from the separately retained certification matrix.  A sound
         * normalization may introduce another occurrence of an already admitted
         * variadic operator (for example a binary relational PLUS beneath JOIN).
         * Bind the immutable Alloy law declaration to that exact repaired carrier
         * before projection; this does not certify a new rewrite or orient an
         * equality.
         */
        private void certifyRepairContainerLaws() {
            Set<EGraphNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (NormalForm normalForm : normalForms) {
                certifyRepairContainerLaws(
                        normalForm.getMatrixEGraph(), visited);
            }
        }

        private void certifyRepairContainerLaws(
                EGraphNode node,
                Set<EGraphNode> visited) {
            if (node == null || !visited.add(node)) {
                return;
            }
            if (node.hasFlatLicense()) {
                PortSchema schema = containerSchema(
                        node.getFlexibleArityKind(),
                        new OnePortSchema(outputType(node)));
                String operator = semanticHead(node);
                recordContainerLaws(operator, certifiedLaws(
                        schema,
                        semanticProfile,
                        node.getOpcode(),
                        operator,
                        outputType(node),
                        true));
            }
            if (!node.isFlexibleArity()
                    && node.isOrderInsensitive()
                    && isCertifiedFixedCommutative(node.getOpcode())
                    && node.getChildren().size() == 2) {
                certifyRepairFixedCommutativeLaws(node);
            }
            for (EGraphNode child : node.getChildren()) {
                certifyRepairContainerLaws(child, visited);
            }
        }

        private void certifyRepairFixedCommutativeLaws(EGraphNode source) {
            List<List<GraphType>> candidates = new ArrayList<>(2);
            for (EGraphNode child : source.getChildren()) {
                LinkedHashSet<GraphType> alternatives = new LinkedHashSet<>();
                if (child.getOpcode() == Opcode.VARIABLE) {
                    alternatives.add(bindingType(child));
                }
                alternatives.add(outputType(child));
                candidates.add(List.copyOf(alternatives));
            }
            String operator = semanticHead(source);
            for (GraphType left : candidates.get(0)) {
                for (GraphType right : candidates.get(1)) {
                    GraphType carrier = source.getOpcode() == Opcode.IFF
                            ? GraphType.BOOL
                            : AlloyTypeBridge.commutativeCarrier(
                                    List.of(left, right));
                    BagPortSchema schema = new BagPortSchema(
                            ArityPolicy.exact(2),
                            new OnePortSchema(carrier));
                    recordContainerLaws(operator, certifiedLaws(
                            schema,
                            semanticProfile,
                            source.getOpcode(),
                            operator,
                            outputType(source),
                            false));
                }
            }
        }

        private void recordCertifiedSetPartition(
                EGraphNode source,
                ContainerApplicationTrace trace) {
            if (!(trace.schema() instanceof SetPortSchema)) {
                return;
            }
            CertifiedSetOperandPartition candidate =
                    new CertifiedSetOperandPartition(source, trace);
            long lineage = source.getCertificationOccurrenceLineage();
            if (nonuniformSetPartitionLineages.contains(lineage)) {
                return;
            }
            CertifiedSetOperandPartition prior =
                    certifiedSetPartitionsByLineage.putIfAbsent(
                            lineage, candidate);
            if (prior != null && !prior.samePartition(candidate)) {
                certifiedSetPartitionsByLineage.remove(lineage);
                nonuniformSetPartitionLineages.add(lineage);
            }
        }

        private void mirrorCertifiedSetPartitionsToRepairMatrices() {
            repairedSetOperandPartitions.clear();
            if (certifiedSetPartitionsByLineage.isEmpty()) {
                return;
            }
            Set<EGraphNode> visited = Collections.newSetFromMap(
                    new IdentityHashMap<>());
            ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
            for (NormalForm form : normalForms) {
                if (form.getMatrixEGraph() != null) {
                    pending.add(form.getMatrixEGraph());
                }
            }
            while (!pending.isEmpty()) {
                EGraphNode candidate = pending.removeFirst();
                if (!visited.add(candidate)) {
                    continue;
                }
                CertifiedSetOperandPartition partition =
                        certifiedSetPartitionsByLineage.get(
                                candidate.getCertificationOccurrenceLineage());
                if (partition != null
                        && partition.deduplicated()
                        && candidate.getOpcode() == partition.sourceOpcode
                        && candidate.isSetFlexibleArity()
                        && candidate.getChildren().size() == partition.inputArity) {
                    partition.inputFibers(candidate);
                    repairedSetOperandPartitions.put(candidate, partition);
                }
                pending.addAll(candidate.getChildren());
            }
        }

        private TypedSlotContext addParameters(
                TypedSlotContext context,
                Map<String, TypedSlot> bindings,
                List<QuantiVar> parameters) {
            Map<GraphType, Integer> ordinals = new TreeMap<>();
            TypedSlotContext result = context;
            for (int index = 0; index < parameters.size(); index++) {
                QuantiVar parameter = parameters.get(index);
                GraphType carrier = bindingType(parameter);
                int ordinal = ordinals.getOrDefault(carrier, 0);
                ordinals.put(carrier, ordinal + 1);
                TypedSlot slot = TypedSlot.of(carrier, SlotAlphabet.SOURCE, ordinal);
                result = result.plus(slot);
                registerBinding(bindings, parameter, slot);
            }
            return result;
        }

        private void mirrorCertifiedSourcePlansToRepairMatrices() {
            Map<Long, BinderBlockDescriptor> localDescriptorsByLineage =
                    new LinkedHashMap<>();
            Map<Long, Map<String, Integer>> localCoordinatesByLineage =
                    new LinkedHashMap<>();
            for (EGraphNode source : new ArrayList<>(localBinderDescriptors.keySet())) {
                long lineage = source.getSourceOccurrenceLineage();
                BinderBlockDescriptor descriptor =
                        localBinderDescriptors.get(source);
                Map<String, Integer> coordinates =
                        localBinderSourceCoordinates.get(source);
                BinderBlockDescriptor priorDescriptor =
                        localDescriptorsByLineage.putIfAbsent(
                                lineage, descriptor);
                Map<String, Integer> priorCoordinates =
                        localCoordinatesByLineage.putIfAbsent(
                                lineage, coordinates);
                if (priorDescriptor != null
                        && (!priorDescriptor.equals(descriptor)
                                || !Objects.equals(
                                        priorCoordinates, coordinates))) {
                    throw new IllegalStateException(
                            "Duplicated local-binder lineage carries different proof payloads "
                                    + lineage);
                }
            }
            localBinderDescriptors.clear();
            localBinderSourceCoordinates.clear();
            Set<EGraphNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
            for (NormalForm form : normalForms) {
                if (form.getMatrixEGraph() != null) {
                    pending.add(form.getMatrixEGraph());
                }
            }
            while (!pending.isEmpty()) {
                EGraphNode candidate = pending.removeFirst();
                if (!visited.add(candidate)) {
                    continue;
                }
                if (isLocalBinder(candidate)
                        && !localBinderDescriptors.containsKey(candidate)) {
                    long lineage = candidate.getSourceOccurrenceLineage();
                    BinderBlockDescriptor descriptor =
                            localDescriptorsByLineage.get(lineage);
                    Map<String, Integer> coordinates =
                            localCoordinatesByLineage.get(lineage);
                    if (descriptor == null || coordinates == null) {
                        throw new IllegalStateException(
                                "A Fast Rewrite IR local binder has no faithful source plan");
                    }
                    localBinderDescriptors.put(candidate, descriptor);
                    localBinderSourceCoordinates.put(candidate, coordinates);
                }
                pending.addAll(candidate.getChildren());
            }

            Map<Long, DependentChainSourceBinding> chainsByLineage =
                    new LinkedHashMap<>();
            for (Map.Entry<EGraphNode, DependentChainSourceBinding> entry
                    : new ArrayList<>(dependentChainSourceBindings.entrySet())) {
                long lineage = entry.getKey().getSourceOccurrenceLineage();
                DependentChainSourceBinding prior =
                        chainsByLineage.putIfAbsent(lineage, entry.getValue());
                if (prior != null
                        && !prior.sameTransferPlanAs(entry.getValue())) {
                    throw new IllegalStateException(
                            "Duplicated dependent-chain lineage carries different proof payloads "
                                    + lineage);
                }
            }
            dependentChainSourceBindings.clear();
            visited.clear();
            pending.clear();
            for (NormalForm form : normalForms) {
                if (form.getMatrixEGraph() != null) {
                    pending.add(form.getMatrixEGraph());
                }
            }
            while (!pending.isEmpty()) {
                EGraphNode candidate = pending.removeFirst();
                if (!visited.add(candidate)) {
                    continue;
                }
                if (candidate.getOpcode() == Opcode.JOIN
                        || candidate.getOpcode() == Opcode.ARROW) {
                    long lineage = candidate.getSourceOccurrenceLineage();
                    DependentChainSourceBinding binding = chainsByLineage.get(lineage);
                    if (binding != null) {
                        dependentChainSourceBindings.put(
                                candidate, binding.transferTo(candidate));
                    }
                }
                pending.addAll(candidate.getChildren());
            }
        }

        private TypedInvocation buildPhase(
                NormalForm phase,
                TypedSlotContext ambient,
                Map<String, TypedSlot> inheritedBindings) {
            if (!declaredForms.contains(phase)) {
                throw new IllegalStateException("Temporal child is absent from IRAgent.normalForms()");
            }
            if (!builtForms.add(phase)) {
                throw new IllegalStateException("A temporal phase is reachable through two structural parents");
            }
            activePhases.addLast(phase);
            try {
            certifyPhaseLocalBindings(phase, inheritedBindings);
            BinderPlan plan = binderPlan(phase.getMatrixQuantiVars(), ambient);
            if (phaseBinderDescriptors.put(phase, plan.descriptor) != null) {
                throw new IllegalStateException(
                        "A temporal phase received two certified binder descriptors");
            }
            if (phaseSourceCoordinates.put(phase, plan.sourceToCoordinate) != null) {
                throw new IllegalStateException(
                        "A temporal phase received two certified coordinate maps");
            }
            TypedSlotContext bodyContext = ambient.union(plan.occurrence.codomain());
            Map<String, TypedSlot> bodyBindings = new LinkedHashMap<>(inheritedBindings);
            for (int index = 0; index < plan.variables.size(); index++) {
                TypedSlot descriptorSlot = plan.coordinates.get(index).canonicalSlot();
                registerBinding(
                        bodyBindings,
                        plan.variables.get(index),
                        plan.occurrence.apply(descriptorSlot));
            }
            for (QuantiVar inherited : phase.getInheritedQuantiVars()) {
                if (!hasBinding(bodyBindings, inherited)) {
                    throw new IllegalStateException(
                            "Temporal phase lost inherited binder " + inherited.getDeBruijnKey());
                }
            }

            Map<String, TemporalReferencePlan> temporalReferences = buildTemporalReferences(
                    phase, bodyContext, bodyBindings);
            EGraphNode matrix = phase.getCertificationMatrixEGraph();
            OnePort matrixValue = matrix == null
                    ? OnePort.invocation(bodyContext, insert(constantNode(
                            "true", GraphType.BOOL, bodyContext)))
                    : buildMatrixOperand(
                            phase,
                            matrix,
                            bodyContext,
                            bodyBindings,
                            temporalReferences);
            TypedInvocation matrixInvocation = asInvocation(matrixValue, bodyContext, "phase-matrix");
            if (plan.variables.isEmpty()) {
                return matrixInvocation;
            }

            BindBlockPortSchema schema = new BindBlockPortSchema(
                    plan.descriptor, new OnePortSchema(matrixInvocation.outputType()));
            BindBlockPort block = new BindBlockPort(
                    schema,
                    ambient,
                    plan.occurrence,
                    OnePort.invocation(bodyContext, matrixInvocation));
            OperatorDeclaration declaration = OperatorDeclaration.monomorphic(
                    "ALLOY/QT",
                    Collections.singletonList(schema),
                    matrixInvocation.outputType(),
                    Collections.emptyMap(),
                    null);
            return insert(TypedENode.construct(
                    declaration.instantiateMonomorphic(),
                    ambient,
                    Collections.singletonList(block)));
            } finally {
                NormalForm removed = activePhases.removeLast();
                if (removed != phase) {
                    throw new IllegalStateException(
                            "Temporal phase construction stack lost lexical order");
                }
            }
        }

        private OnePort buildMatrixOperand(
                NormalForm phase,
                EGraphNode matrix,
                TypedSlotContext context,
                Map<String, TypedSlot> bindings,
                Map<String, TemporalReferencePlan> temporalReferences) {
            Map<String, String> rootNames = identitySlotNames(matrix);
            if (phase == normalForms.get(0)
                    && matrix.getOpcode() == Opcode.PREDICATE
                    && matrix.getSourceName() != null
                    && matrix.getChildClasses().size() == 1) {
                return buildChildOperand(
                        matrix.getChildClasses().get(0),
                        rootNames,
                        context,
                        bindings,
                        temporalReferences);
            }
            return buildOperand(
                    matrix,
                    rootNames,
                    context,
                    bindings,
                    temporalReferences);
        }

        private void certifyPhaseLocalBindings(
                NormalForm phase,
                Map<String, TypedSlot> bindings) {
            List<NormalForm.PhaseLocalBindingImport> imports =
                    phase.getPhaseLocalBindingImports();
            if (imports.isEmpty()) {
                return;
            }
            List<PhaseLocalBindingCertificate> certificates = new ArrayList<>(
                    imports.size());
            for (NormalForm.PhaseLocalBindingImport imported : imports) {
                if (!phase.getPhasePath().equals(imported.targetPhasePath())) {
                    throw new IllegalStateException(
                            "A phase-local binder import crossed its target phase");
                }
                LocalBinderFrame owner = null;
                java.util.Iterator<LocalBinderFrame> frames =
                        activeLocalBinders.descendingIterator();
                while (frames.hasNext()) {
                    LocalBinderFrame candidate = frames.next();
                    if (candidate.ownerPhase == imported.ownerPhase()
                            && sameBinderOccurrence(
                                    candidate.source, imported.ownerBinder())
                            && candidate.sourceCoordinates.containsKey(
                                    imported.variable().getName())) {
                        owner = candidate;
                        break;
                    }
                }
                if (owner == null) {
                    throw new IllegalStateException(
                            "A temporal phase local import has no active owner binder");
                }
                Integer coordinate = owner.sourceCoordinates.get(
                        imported.variable().getName());
                if (coordinate == null) {
                    throw new IllegalStateException(
                            "A temporal phase local import has no owner coordinate");
                }
                TypedSlot ownerSlot = owner.slotsByCoordinate.get(coordinate);
                TypedSlot importedSlot = exactBinding(bindings, imported.variable());
                if (ownerSlot == null || importedSlot == null
                        || !ownerSlot.equals(importedSlot)) {
                    throw new IllegalStateException(
                            "A temporal phase local import changed its owner slot");
                }
                if (!ownerSlot.type().equals(bindingType(imported.variable()))) {
                    throw new IllegalStateException(
                            "A temporal phase local import changed its certified type");
                }
                Integer ownerPhase = phaseIndices.get(imported.ownerPhase());
                if (ownerPhase == null) {
                    throw new IllegalStateException(
                            "A temporal phase local import names an undeclared owner phase");
                }
                certificates.add(new PhaseLocalBindingCertificate(
                        imported,
                        ownerPhase,
                        owner.descriptor,
                        coordinate));
            }
            if (phaseLocalBindingCertificates.put(
                    phase, Collections.unmodifiableList(certificates)) != null) {
                throw new IllegalStateException(
                        "A temporal phase received phase-local certificates twice");
            }
        }

        private static boolean sameBinderOccurrence(
                EGraphNode active,
                EGraphNode imported) {
            if (active == imported) {
                return true;
            }
            long activeLineage = active.getSourceOccurrenceLineage();
            long importedLineage = imported.getSourceOccurrenceLineage();
            return activeLineage > 0L && activeLineage == importedLineage;
        }

        private static TypedSlot exactBinding(
                Map<String, TypedSlot> bindings,
                QuantiVar variable) {
            TypedSlot canonical = bindings.get(variable.getName());
            if (canonical == null) {
                canonical = bindings.get(variable.getDeBruijnKey());
            }
            if (canonical != null) {
                return canonical;
            }
            TypedSlot result = null;
            for (String alias : variable.getOriginalNames()) {
                TypedSlot candidate = bindings.get(alias);
                if (candidate == null) {
                    continue;
                }
                if (result != null && !result.equals(candidate)) {
                    throw new IllegalStateException(
                            "One phase-local variable resolves to two active slots");
                }
                result = candidate;
            }
            return result;
        }

        private Map<String, TemporalReferencePlan> buildTemporalReferences(
                NormalForm phase,
                TypedSlotContext context,
                Map<String, TypedSlot> bindings) {
            Map<String, TemporalReferencePlan> result = new HashMap<>();
            List<NormalForm> children = phase.getTemporalChildren();
            for (int index = 0; index < children.size();) {
                NormalForm first = children.get(index);
                TemporalOp operation = first.getTemporalOp();
                DeferredTemporalReferencePlan plan;
                String reference;
                if (isBinaryLeft(operation)) {
                    if (index + 1 >= children.size()
                            || !isMatchingBinaryRight(operation, children.get(index + 1).getTemporalOp())) {
                        throw new IllegalStateException(
                                "Malformed binary temporal phase pair at index " + index);
                    }
                    plan = new DeferredTemporalReferencePlan(
                            binaryBase(operation),
                            List.of(first, children.get(index + 1)));
                    reference = temporalReference(index, 2);
                    index += 2;
                } else {
                    plan = new DeferredTemporalReferencePlan(
                            operation.name(), Collections.singletonList(first));
                    reference = temporalReference(index, 1);
                    index++;
                }
                if (result.put(reference, plan) != null) {
                    throw new IllegalStateException(
                            "A temporal reference name was issued twice");
                }
                if (!plan.requiresPhaseLocalContext()) {
                    plan.resolve(context, new LinkedHashMap<>(bindings));
                }
            }
            return result;
        }

        private interface TemporalReferencePlan {
            TypedInvocation resolve(
                    TypedSlotContext context,
                    Map<String, TypedSlot> bindings);
        }

        private final class DeferredTemporalReferencePlan
                implements TemporalReferencePlan {
            private final String operation;
            private final List<NormalForm> children;
            private TypedInvocation resolved;
            private TypedSlotContext resolvedContext;

            private DeferredTemporalReferencePlan(
                    String operation,
                    List<NormalForm> children) {
                this.operation = Objects.requireNonNull(operation, "temporal operation");
                this.children = List.copyOf(children);
            }

            private boolean requiresPhaseLocalContext() {
                for (NormalForm child : children) {
                    if (!child.getPhaseLocalBindingImports().isEmpty()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public TypedInvocation resolve(
                    TypedSlotContext context,
                    Map<String, TypedSlot> bindings) {
                if (resolved != null) {
                    if (!resolvedContext.equals(context)) {
                        throw new IllegalStateException(
                                "One temporal phase was invoked under two binder contexts");
                    }
                    return resolved;
                }
                List<TypedInvocation> values = new ArrayList<>(children.size());
                for (NormalForm child : children) {
                    values.add(buildPhase(
                            child, context, new LinkedHashMap<>(bindings)));
                }
                resolved = temporalNode(operation, context, values);
                resolvedContext = context;
                return resolved;
            }
        }

        private TypedInvocation temporalNode(
                String operation,
                TypedSlotContext context,
                List<TypedInvocation> children) {
            List<PortSchema> schemas = new ArrayList<>(children.size());
            List<PortValue> ports = new ArrayList<>(children.size());
            for (TypedInvocation child : children) {
                schemas.add(new OnePortSchema(child.outputType()));
                ports.add(OnePort.invocation(context, child));
            }
            OperatorDeclaration declaration = OperatorDeclaration.monomorphic(
                    "ALLOY/TEMPORAL/" + operation,
                    schemas,
                    GraphType.BOOL,
                    Collections.emptyMap(),
                    null);
            return insert(TypedENode.construct(
                    declaration.instantiateMonomorphic(), context, ports));
        }

        private OnePort buildOperand(
                EGraphNode node,
                Map<String, String> slotNames,
                TypedSlotContext context,
                Map<String, TypedSlot> bindings,
                Map<String, TemporalReferencePlan> temporalReferences) {
            node.requireAdmittedArity();
            if (node.getOpcode() == Opcode.END) {
                throw new IllegalStateException("END survived normal-form cleanup");
            }
            if (node.getOpcode() == Opcode.REF
                    && node.getSourceName() != null
                    && node.getSourceName().startsWith("temporal[")) {
                TemporalReferencePlan temporal = temporalReferences.get(node.getSourceName());
                if (temporal == null) {
                    throw new IllegalStateException(
                            "Unresolved temporal reference " + node.getSourceName());
                }
                return OnePort.invocation(
                        context, temporal.resolve(context, bindings));
            }
            if (node.getOpcode() == Opcode.VARIABLE) {
                String local = firstNonempty(node.getAlphaName(), node.getSourceName());
                String resolved = slotNames.getOrDefault(local, local);
                TypedSlot slot = bindings.get(resolved);
                if (slot == null) {
                    slot = bindings.get(node.getSourceName());
                }
                if (slot == null) {
                    throw new IllegalStateException(
                            "Unbound normalized variable " + local + " in " + node.getOpcode());
                }
                return OnePort.slot(context, slot);
            }
            if (isLocalBinder(node)) {
                return OnePort.invocation(
                        context,
                        buildLocalBinder(node, slotNames, context, bindings, temporalReferences));
            }

            InvocationKey key = new InvocationKey(
                    node.getEClass().getId(), slotNames, context, semanticHead(node));
            TypedInvocation remembered = memo.get(key);
            if (remembered != null) {
                return OnePort.invocation(context, remembered);
            }
            if (!active.add(key)) {
                throw new IllegalStateException(
                        "Normalized source contains a recursive e-class invocation at " + semanticHead(node));
            }
            try {
                if (node.getOpcode() == Opcode.JOIN
                        || node.getOpcode() == Opcode.ARROW) {
                    OnePort result = constructDependentChainOperand(
                            node,
                            slotNames,
                            context,
                            bindings,
                            temporalReferences);
                    if (result != null) {
                        if (result.leaf() instanceof InvocationPortLeaf) {
                            memo.put(key, ((InvocationPortLeaf) result.leaf()).invocation());
                        }
                        return result;
                    }
                }
                if (node.hasFlatLicense()) {
                    OnePort result = constructCertifiedFlatOperand(
                            node,
                            slotNames,
                            context,
                            bindings,
                            temporalReferences);
                    if (result.leaf() instanceof InvocationPortLeaf) {
                        memo.put(key, ((InvocationPortLeaf) result.leaf())
                                .invocation());
                    }
                    return result;
                }
                List<OnePort> operands = new ArrayList<>();
                for (EClassRef child : node.getChildClasses()) {
                    operands.add(buildChildOperand(
                            child, slotNames, context, bindings, temporalReferences));
                }
                NodeConstruction typed = constructNode(node, context, operands);
                if (node.getOpcode() == Opcode.CALL) {
                    String occurrencePath = sourceOccurrencePaths.get(node);
                    if (occurrencePath == null) {
                        throw new IllegalStateException(
                                "A CALL source lacks a deterministic occurrence path");
                    }
                    CallOccurrenceCertificate call = CallOccurrenceCertificate.create(
                            CallMetadata.require(node),
                            occurrencePath,
                            typed.node,
                            operands);
                    recordCallOccurrence(call);
                }
                TypedInvocation invocation = insert(typed);
                memo.put(key, invocation);
                return OnePort.invocation(context, invocation);
            } finally {
                active.remove(key);
            }
        }

        private void recordCallOccurrence(
                CallOccurrenceCertificate occurrence) {
            CallOccurrenceCertificate prior = callOccurrencesById.putIfAbsent(
                    occurrence.occurrenceId(), occurrence);
            if (prior != null) {
                if (!prior.sameParserOccurrencePayloadAs(occurrence)) {
                    throw new IllegalStateException(
                            "Duplicated CALL occurrence carries a different typed payload: "
                                    + occurrence.occurrenceId());
                }
                return;
            }
            callOccurrenceCertificates.add(occurrence);
            constructionSources.recordCall(occurrence);
        }

        private OnePort constructDependentChainOperand(
                EGraphNode source,
                Map<String, String> slotNames,
                TypedSlotContext context,
                Map<String, TypedSlot> bindings,
                Map<String, TemporalReferencePlan> temporalReferences) {
            DependentChainKind kind = source.getOpcode() == Opcode.JOIN
                    ? DependentChainKind.JOIN : DependentChainKind.ARROW;
            DependentChainApplication application;
            try {
                application = dependentChainApplication(
                        source,
                        kind,
                        slotNames,
                        context,
                        bindings,
                        temporalReferences,
                        new HashSet<>());
            } catch (UnsupportedDependentChain
                    | DependentChainTheory.UnsupportedFlattening exception) {
                return null;
            }
            requireDependentResultProof(source, application);
            String occurrencePath = sourceOccurrencePaths.get(source);
            if (occurrencePath == null) {
                throw new IllegalStateException(
                        "A dependent source chain lacks a stable occurrence path");
            }
            StructuralKey sourceCommitment =
                    DependentChainSourceBinding.sourceOccurrenceCommitment(
                            source,
                            occurrencePath,
                            application.structuralKey());
            constructionSources.recordDependentChain(application, sourceCommitment);
            CertifiedDependentChainConstruction construction =
                    TypedENode.constructDependentChainCertified(
                            application, semanticProfile, sourceCommitment);
            dependentChainConstructions.add(construction.certificate());
            DependentChainSourceBinding sourceBinding =
                    new DependentChainSourceBinding(
                            source, occurrencePath, construction.certificate());
            DependentChainSourceBinding previous = dependentChainSourceBindings.putIfAbsent(
                    source, sourceBinding);
            if (previous != null
                    && !previous.sourceOccurrenceCommitment().equals(
                            sourceBinding.sourceOccurrenceCommitment())) {
                throw new IllegalStateException(
                        "One source chain received incompatible dependent type proofs");
            }
            ensureQuiescent();
            CertifiedInsertionResult insertion = graph.insertNodeConstructed(
                    construction, graph.coherentWitnessFamily());
            return OnePort.invocation(
                    context, finishInsertion(context, insertion));
        }

        private DependentChainApplication dependentChainApplication(
                EGraphNode source,
                DependentChainKind kind,
                Map<String, String> slotNames,
                TypedSlotContext context,
                Map<String, TypedSlot> bindings,
                Map<String, TemporalReferencePlan> temporalReferences,
                Set<Integer> activeChainClasses) {
            source.requireAdmittedArity();
            if (source.getChildren().size() != 2
                    || (kind == DependentChainKind.JOIN
                            ? source.getOpcode() != Opcode.JOIN
                            : source.getOpcode() != Opcode.ARROW)) {
                throw new IllegalArgumentException(
                        "Dependent chain source is not one exact binary " + kind);
            }
            int classId = source.getEClass().getId();
            if (!activeChainClasses.add(classId)) {
                throw new IllegalStateException(
                        "Dependent chain contains a recursive source e-class");
            }
            try {
                DependentChainInput left = dependentChainInput(
                        source.getChildClasses().get(0),
                        kind,
                        slotNames,
                        context,
                        bindings,
                        temporalReferences,
                        activeChainClasses);
                DependentChainInput right = dependentChainInput(
                        source.getChildClasses().get(1),
                        kind,
                        slotNames,
                        context,
                        bindings,
                        temporalReferences,
                        activeChainClasses);
                DependentChainApplication application;
                try {
                    application = new DependentChainApplication(kind, left, right);
                } catch (DependentChainTheory.UnsupportedFlattening exception) {
                    throw new UnsupportedDependentChain(exception);
                } catch (DependentBoundaryCorrespondence.UnsupportedCorrespondence exception) {
                    throw new UnsupportedDependentChain(exception);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException(
                            "A concrete dependent " + kind
                                    + " source violates its chain equation",
                            exception);
                }
                requireDependentResultProof(source, application);
                return application;
            } finally {
                activeChainClasses.remove(classId);
            }
        }

        private static void requireDependentResultProof(
                EGraphNode source,
                DependentChainApplication application) {
            GraphType storedResult = outputType(source);
            try {
                DependentChainTheory.LeafTypeRule rule =
                        DependentChainTheory.requireLeafTypeProof(
                                storedResult,
                                application.outputTypeDag(),
                                source.getExactAlloyType());
                DependentChainTheory.leafTypeProof(
                        rule,
                        storedResult,
                        application.outputTypeDag(),
                        source.getExactAlloyType());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "A concrete dependent " + application.kind()
                                + " source has another exact result type: source="
                                + storedResult + ", derived="
                                + application.outputType(),
                        exception);
            }
        }

        private DependentChainInput dependentChainInput(
                EClassRef childRef,
                DependentChainKind kind,
                Map<String, String> outerNames,
                TypedSlotContext context,
                Map<String, TypedSlot> bindings,
                Map<String, TemporalReferencePlan> temporalReferences,
                Set<Integer> activeChainClasses) {
            EClassRef canonical = childRef.canonical();
            EGraphNode child = canonical.getEClass().getRepresentative();
            Map<String, String> childNames = composeSlotNames(
                    canonical.getSlotMap(), outerNames);
            boolean same = kind == DependentChainKind.JOIN
                    ? child.getOpcode() == Opcode.JOIN
                    : child.getOpcode() == Opcode.ARROW;
            if (same) {
                return dependentChainApplication(
                        child,
                        kind,
                        childNames,
                        context,
                        bindings,
                        temporalReferences,
                        activeChainClasses);
            }
            OnePort leaf = buildOperand(
                    child,
                    childNames,
                    context,
                    bindings,
                    temporalReferences);
            try {
                GraphType storedType = leaf.schema().type();
                GraphType relationType = AlloyTypeBridge.isRelationFamily(storedType)
                        ? storedType
                        : DependentChainTheory.relationViewFromStoredType(storedType);
                ExactAlloyType exactType = child.getExactAlloyType();
                DependentTypeDag typeDag = exactType != null
                                && (exactType.kind() == ExactAlloyType.Kind.RELATION
                                        || exactType.kind()
                                                == ExactAlloyType.Kind.EMPTY_RELATION)
                        ? dependentTypeDagForSource(child, new HashSet<>())
                        : DependentTypeDag.fromRelationFamilyType(relationType);
                return new DependentChainLeaf(leaf, typeDag, exactType);
            } catch (DependentChainTheory.UnsupportedFlattening
                    | DependentBoundaryCorrespondence.UnsupportedCorrespondence exception) {
                throw new UnsupportedDependentChain(exception);
            }
        }

        private DependentTypeDag dependentTypeDagForSource(
                EGraphNode source,
                Set<Integer> activeTypeClasses) {
            ExactAlloyType exactType = source.getExactAlloyType();
            if (exactType == null
                    || (exactType.kind() != ExactAlloyType.Kind.RELATION
                            && exactType.kind()
                                    != ExactAlloyType.Kind.EMPTY_RELATION)) {
                throw new DependentChainTheory.UnsupportedFlattening(
                        "A dependent DAG leaf lacks an exact relation family");
            }
            DependentTypeDag parserDag = AlloyTypeBridge.dependentTypeDag(
                    exactType);
            if (source.getOpcode() != Opcode.PLUS
                    && source.getOpcode() != Opcode.INTERSECT) {
                return parserDag;
            }
            int classId = source.getEClass().getId();
            if (!activeTypeClasses.add(classId)) {
                throw new IllegalStateException(
                        "A dependent set-type DAG contains a recursive e-class");
            }
            try {
                List<DependentTypeDag> operands = new ArrayList<>();
                for (EClassRef childRef : source.getChildClasses()) {
                    EGraphNode child = childRef.canonical().getEClass()
                            .getRepresentative();
                    operands.add(dependentTypeDagForSource(
                            child, activeTypeClasses));
                }
                if (operands.isEmpty()) {
                    throw new DependentChainTheory.UnsupportedFlattening(
                            "A dependent set-type DAG has no nonempty operands");
                }
                DependentTypeDag derived = operands.size() == 1
                        ? operands.get(0)
                        : source.getOpcode() == Opcode.PLUS
                                ? DependentTypeDag.union(operands)
                                : DependentTypeDag.intersection(operands);
                if (!derived.sameOccurrenceEvidenceAs(parserDag)) {
                    throw new IllegalStateException(
                            "A concrete set operator disagrees with its independently "
                                    + "derived dependent type DAG: parser="
                                    + parserDag + ", derived=" + derived);
                }
                return derived;
            } finally {
                activeTypeClasses.remove(classId);
            }
        }

        private static final class UnsupportedDependentChain
                extends RuntimeException {
            private UnsupportedDependentChain(Throwable cause) {
                super(cause);
            }
        }

        private OnePort buildChildOperand(
                EClassRef child,
                Map<String, String> outerNames,
                TypedSlotContext context,
                Map<String, TypedSlot> bindings,
                Map<String, TemporalReferencePlan> temporalReferences) {
            EClassRef canonical = child.canonical();
            return buildOperand(
                    canonical.getEClass().getRepresentative(),
                    composeSlotNames(canonical.getSlotMap(), outerNames),
                    context,
                    bindings,
                    temporalReferences);
        }

        private OnePort constructCertifiedFlatOperand(
                EGraphNode source,
                Map<String, String> slotNames,
                TypedSlotContext context,
                Map<String, TypedSlot> bindings,
                Map<String, TemporalReferencePlan> temporalReferences) {
            source.requireAdmittedArity();
            GraphType output = outputType(source);
            PortSchema container = containerSchema(
                    source.getFlexibleArityKind(), new OnePortSchema(output));
            String operatorName = semanticHead(source);
            ContainerLawDeclaration laws = certifiedLaws(
                    container,
                    semanticProfile,
                    source.getOpcode(),
                    operatorName,
                    output,
                    true);
            recordContainerLaws(operatorName, laws);
            InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                    operatorName,
                    Collections.singletonList(container),
                    output,
                    Collections.singletonMap(PortPath.at(0), laws),
                    0).instantiateMonomorphic();
            FlatApplication application = buildFlatApplication(
                    source,
                    slotNames,
                    context,
                    bindings,
                    temporalReferences,
                    operator,
                    source.getExactAlloyType(),
                    new HashSet<>());
            constructionSources.recordFlat(application);
            CertifiedFlatConstruction construction = TypedENode.flatConstructCertified(
                    application, this::insert, semanticProfile);
            FlatConstructionCertificate certificate = construction.certificate();
            flatConstructions.add(certificate);
            recordCertifiedSetPartition(source, certificate.containerTrace());
            if (construction.collapsedToSingleton()) {
                return construction.singleton();
            }
            return OnePort.invocation(context, insert(construction));
        }

        private FlatApplication buildFlatApplication(
                EGraphNode source,
                Map<String, String> slotNames,
                TypedSlotContext context,
                Map<String, TypedSlot> bindings,
                Map<String, TemporalReferencePlan> temporalReferences,
                InstantiatedOperator operator,
                ExactAlloyType carrierExactType,
                Set<Integer> activeFlatClasses) {
            if (!source.hasFlatLicense()
                    || !semanticHead(source).equals(operator.operator())
                    || (!outputType(source).equals(operator.outputType())
                            && !isRelationalUnionWidening(
                                    source,
                                    operator.outputType(),
                                    carrierExactType))) {
                throw new IllegalArgumentException(
                        "Visible flat source changed its exact operator instance");
            }
            int classId = source.getEClass().getId();
            if (!activeFlatClasses.add(classId)) {
                throw new IllegalStateException(
                        "Visible flat source contains a recursive same-head occurrence");
            }
            try {
                List<FlatInput> inputs = new ArrayList<>();
                for (EClassRef childRef : source.getChildClasses()) {
                    EClassRef canonical = childRef.canonical();
                    EGraphNode child = canonical.getEClass().getRepresentative();
                    Map<String, String> childNames = composeSlotNames(
                            canonical.getSlotMap(), slotNames);
                    if (source.sameFlatOperatorInstance(child)
                            && semanticHead(source).equals(semanticHead(child))) {
                        inputs.add(buildFlatApplication(
                                child,
                                childNames,
                                context,
                                bindings,
                                temporalReferences,
                                operator,
                                carrierExactType,
                                activeFlatClasses));
                        continue;
                    }
                    OnePort leaf = buildOperand(
                            child,
                            childNames,
                            context,
                            bindings,
                            temporalReferences);
                    if (!leaf.schema().type().equals(operator.outputType())) {
                        if (source.getOpcode() == Opcode.PLUS
                                || source.getOpcode() == Opcode.INTERSECT) {
                            leaf = asRelationalOperand(
                                    leaf, context, operator.outputType());
                        } else {
                            throw new IllegalStateException(
                                    "Flat source operand has a different exact result type");
                        }
                    }
                    inputs.add(new FlatLeaf(leaf));
                }
                return new FlatApplication(operator, context, inputs);
            } finally {
                activeFlatClasses.remove(classId);
            }
        }

        private static boolean isRelationalUnionWidening(
                EGraphNode source,
                GraphType outerType,
                ExactAlloyType carrierExactType) {
            return source.getOpcode() == Opcode.PLUS
                    && source.getExactAlloyType() != null
                    && carrierExactType != null
                    && AlloyTypeBridge.graphType(carrierExactType).equals(outerType)
                    && source.getExactAlloyType()
                            .isParserCertifiedRelationSubfamilyOf(
                                    carrierExactType);
        }

        private NodeConstruction constructNode(
                EGraphNode source,
                TypedSlotContext context,
                List<OnePort> operands) {
            GraphType output = outputType(source);
            source.requireAdmittedArity();
            if (source.hasFlatLicense()) {
                throw new IllegalStateException(
                        "Flat Alloy operators require concrete source-law certification");
            }
            List<OnePort> typedOperands = operands;
            if (!source.isFlexibleArity() && source.isOrderInsensitive()) {
                if (!isCertifiedFixedCommutative(source.getOpcode())) {
                    throw new IllegalStateException(
                            "No Alloy signature certificate for fixed commutativity of "
                                    + source.getOpcode());
                }
                typedOperands = fixedCommutativeOperands(source, context, operands);
                if (typedOperands.isEmpty()) {
                    throw new IllegalStateException(
                            "A fixed commutative Alloy operator has no operands");
                }
                PortSchema element = typedOperands.get(0).schema();
                if (!typedOperands.stream().allMatch(
                        operand -> operand.schema().equals(element))) {
                    throw new IllegalStateException(
                            "A fixed commutative Alloy operator has heterogeneous operands");
                }
                BagPortSchema container = new BagPortSchema(
                        ArityPolicy.exact(typedOperands.size()), element);
                String operator = semanticHead(source);
                ContainerLawDeclaration laws = certifiedLaws(
                        container,
                        semanticProfile,
                        source.getOpcode(),
                        operator,
                        output,
                        false);
                recordContainerLaws(operator, laws);
                OperatorDeclaration declaration = OperatorDeclaration.monomorphic(
                        operator,
                        Collections.singletonList(container),
                        output,
                        Collections.singletonMap(PortPath.at(0), laws),
                        null);
                InstantiatedOperator instantiated = declaration.instantiateMonomorphic();
                constructionSources.recordContainer(
                        instantiated, PortPath.at(0), context, typedOperands);
                CertifiedContainerConstruction construction =
                        TypedENode.constructContainerCertified(
                                instantiated,
                                PortPath.at(0),
                                context,
                                typedOperands,
                                semanticProfile);
                containerConstructions.add(construction.certificate());
                return NodeConstruction.certified(construction);
            }
            if (!source.hasFlatLicense()
                    && source.isFlexibleArity()
                    && source.isOrderInsensitive()) {
                if (source.getOpcode() != Opcode.DISJOINT) {
                    throw new IllegalStateException(
                            "No Alloy signature certificate for variadic commutativity of "
                                    + source.getOpcode());
                }
                if (operands.isEmpty()) {
                    throw new IllegalStateException(
                            "A variadic commutative Alloy operator has no operands");
                }
                GraphType carrier = AlloyTypeBridge.commutativeCarrier(
                        operandTypes(operands));
                typedOperands = coerceOperands(operands, context, carrier);
                PortSchema element = typedOperands.get(0).schema();
                BagPortSchema container = new BagPortSchema(
                        source.getArityPolicy(), element);
                String operator = semanticHead(source);
                ContainerLawDeclaration laws = certifiedLaws(
                        container,
                        semanticProfile,
                        source.getOpcode(),
                        operator,
                        output,
                        false);
                recordContainerLaws(operator, laws);
                OperatorDeclaration declaration = OperatorDeclaration.monomorphic(
                        operator,
                        Collections.singletonList(container),
                        output,
                        Collections.singletonMap(PortPath.at(0), laws),
                        null);
                InstantiatedOperator instantiated = declaration.instantiateMonomorphic();
                constructionSources.recordContainer(
                        instantiated, PortPath.at(0), context, typedOperands);
                CertifiedContainerConstruction construction =
                        TypedENode.constructContainerCertified(
                                instantiated,
                                PortPath.at(0),
                                context,
                                typedOperands,
                                semanticProfile);
                containerConstructions.add(construction.certificate());
                return NodeConstruction.certified(construction);
            }
            List<PortSchema> schemas = new ArrayList<>(typedOperands.size());
            List<PortValue> ports = new ArrayList<>(typedOperands.size());
            for (OnePort operand : typedOperands) {
                schemas.add(operand.schema());
                ports.add(operand);
            }
            OperatorDeclaration declaration = OperatorDeclaration.monomorphic(
                    semanticHead(source), schemas, output, Collections.emptyMap(), null);
            return NodeConstruction.plain(TypedENode.construct(
                    declaration.instantiateMonomorphic(), context, ports));
        }

        private List<OnePort> fixedCommutativeOperands(
                EGraphNode source,
                TypedSlotContext context,
                List<OnePort> operands) {
            if (source.getOpcode() != Opcode.EQUALS
                    && source.getOpcode() != Opcode.NOT_EQUALS) {
                return operands;
            }
            return coerceOperands(
                    operands,
                    context,
                    AlloyTypeBridge.commutativeCarrier(operandTypes(operands)));
        }

        private List<OnePort> coerceOperands(
                List<OnePort> operands,
                TypedSlotContext context,
                GraphType targetType) {
            List<OnePort> relational = new ArrayList<>(operands.size());
            for (OnePort operand : operands) {
                relational.add(asRelationalOperand(operand, context, targetType));
            }
            return relational;
        }

        private static List<GraphType> operandTypes(List<OnePort> operands) {
            List<GraphType> result = new ArrayList<>(operands.size());
            for (OnePort operand : operands) {
                result.add(operand.schema().type());
            }
            return result;
        }

        private static boolean isCertifiedFixedCommutative(Opcode opcode) {
            return opcode == Opcode.IFF
                    || opcode == Opcode.EQUALS
                    || opcode == Opcode.NOT_EQUALS
                    || opcode == Opcode.IPLUS
                    || opcode == Opcode.MUL;
        }

        private void recordContainerLaws(
                String operator,
                ContainerLawDeclaration laws) {
            for (ContainerLawCertificate certificate : laws.certificates().values()) {
                if (certificate.authority()
                                != ContainerLawCertificate.Authority.ALLOY_PROFILE_THEORY
                        || !semanticProfile.equals(certificate.semanticProfile())
                        || !operator.equals(certificate.operatorIdentity())) {
                    throw new IllegalStateException(
                            "A production Alloy artifact received non-production law evidence");
                }
            }
            List<ContainerLawDeclaration> declarations = certifiedContainerLaws
                    .computeIfAbsent(operator, ignored -> new ArrayList<>());
            for (ContainerLawDeclaration existing : declarations) {
                if (sameCertifiedDeclaration(existing, laws)) {
                    return;
                }
            }
            declarations.add(laws);
        }

        private static boolean sameCertifiedDeclaration(
                ContainerLawDeclaration left,
                ContainerLawDeclaration right) {
            if (left.kind() != right.kind()
                    || !left.certificates().keySet().equals(
                            right.certificates().keySet())) {
                return false;
            }
            for (ContainerLawCertificate.Law law : left.certificates().keySet()) {
                if (!left.certificates().get(law).lawIndex().equals(
                        right.certificates().get(law).lawIndex())) {
                    return false;
                }
            }
            return true;
        }

        private OnePort asRelationalOperand(
                OnePort operand,
                TypedSlotContext context,
                GraphType targetType) {
            if (operand.schema().type().equals(targetType)) {
                return operand;
            }
            RelationalCoercionKey key = new RelationalCoercionKey(operand, targetType);
            TypedInvocation invocation = relationalCoercions.get(key);
            if (invocation == null) {
                invocation = insert(fixedNode(
                        "ALLOY/RELATIONAL-COERCION/" + targetType,
                        targetType,
                        context,
                        Collections.singletonList(operand)));
                relationalCoercions.put(key, invocation);
            } else if (!invocation.callerContext().equals(context)) {
                throw new IllegalStateException("Relational coercion key lost its caller context");
            }
            return OnePort.invocation(context, invocation);
        }

        private TypedInvocation buildLocalBinder(
                EGraphNode source,
                Map<String, String> outerNames,
                TypedSlotContext context,
                Map<String, TypedSlot> bindings,
                Map<String, TemporalReferencePlan> temporalReferences) {
            List<LocalCoordinate> locals = new ArrayList<>();
            int nextDisjointnessClass = 1;
            for (EClassRef childRef : source.getChildClasses()) {
                EClassRef canonical = childRef.canonical();
                EGraphNode child = canonical.getEClass().getRepresentative();
                if (!isRelDecl(child.getOpcode())) {
                    continue;
                }
                Map<String, String> declarationNames = composeSlotNames(
                        canonical.getSlotMap(), outerNames);
                List<EGraphNode> declarationChildren = child.getChildren();
                if (declarationChildren.size() < 2) {
                    throw new IllegalStateException(
                            "A local declaration must retain one domain and at least one variable");
                }
                EGraphNode domain = Objects.requireNonNull(
                        declarationChildren.get(0), "local declaration domain");
                StructuralKey domainKey = domainKey(domain, declarationNames, locals);
                String multiplicity = domainMultiplicity(domain);
                int disjointnessClass = isDisjointDecl(child.getOpcode())
                        ? nextDisjointnessClass++
                        : BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS;
                for (int index = 1; index < declarationChildren.size(); index++) {
                    EGraphNode variable = declarationChildren.get(index);
                    if (variable.getOpcode() != Opcode.VARIABLE) {
                        continue;
                    }
                    String localName = firstNonempty(variable.getAlphaName(), variable.getSourceName());
                    String resolved = declarationNames.getOrDefault(localName, localName);
                    locals.add(new LocalCoordinate(
                            resolved,
                            domainKey,
                            source.getOpcode().name(),
                            multiplicity,
                            disjointnessClass,
                            bindingType(variable)));
                }
            }
            BinderPlan plan = localBinderPlan(source.getOpcode(), locals, context);
            if (localBinderDescriptors.put(source, plan.descriptor) != null) {
                throw new IllegalStateException(
                        "A local matrix binder received two certified descriptors");
            }
            Map<String, Integer> sourceCoordinates = new LinkedHashMap<>();
            for (int index = 0; index < locals.size(); index++) {
                sourceCoordinates.put(
                        locals.get(index).name,
                        plan.sourceToCoordinate.get(index));
            }
            if (localBinderSourceCoordinates.put(source, sourceCoordinates) != null) {
                throw new IllegalStateException(
                        "A local matrix binder received two source-coordinate maps");
            }
            TypedSlotContext bodyContext = context.union(plan.occurrence.codomain());
            Map<String, TypedSlot> bodyBindings = new LinkedHashMap<>(bindings);
            for (int index = 0; index < locals.size(); index++) {
                TypedSlot occurrence = plan.occurrence.apply(
                        plan.coordinates.get(index).canonicalSlot());
                bodyBindings.put(locals.get(index).name, occurrence);
            }
            NormalForm ownerPhase = activePhases.peekLast();
            if (ownerPhase == null) {
                throw new IllegalStateException(
                        "A local binder was constructed outside a temporal phase");
            }
            Map<Integer, TypedSlot> slotsByCoordinate = new LinkedHashMap<>();
            for (int sourceIndex = 0; sourceIndex < locals.size(); sourceIndex++) {
                int coordinate = plan.sourceToCoordinate.get(sourceIndex);
                slotsByCoordinate.put(
                        coordinate,
                        plan.occurrence.apply(
                                plan.coordinates.get(coordinate).canonicalSlot()));
            }
            LocalBinderFrame frame = new LocalBinderFrame(
                    ownerPhase,
                    source,
                    plan.descriptor,
                    sourceCoordinates,
                    slotsByCoordinate);
            activeLocalBinders.addLast(frame);
            TypedInvocation body;
            try {
                List<OnePort> bodies = new ArrayList<>();
                for (EClassRef childRef : source.getChildClasses()) {
                    EClassRef canonical = childRef.canonical();
                    EGraphNode child = canonical.getEClass().getRepresentative();
                    if (isRelDecl(child.getOpcode())) {
                        continue;
                    }
                    bodies.add(buildOperand(
                            child,
                            composeSlotNames(canonical.getSlotMap(), outerNames),
                            bodyContext,
                            bodyBindings,
                            temporalReferences));
                }
                body = bodies.isEmpty()
                        ? insert(constantNode("true", GraphType.BOOL, bodyContext))
                        : bodies.size() == 1
                                ? asInvocation(bodies.get(0), bodyContext, "local-binder-body")
                                : insert(fixedNode(
                                        "ALLOY/LOCAL-BODY/" + source.getOpcode(),
                                        outputType(source),
                                        bodyContext,
                                        bodies));
            } finally {
                LocalBinderFrame removed = activeLocalBinders.removeLast();
                if (removed != frame) {
                    throw new IllegalStateException(
                            "Local binder construction stack lost lexical order");
                }
            }
            BindBlockPortSchema schema = new BindBlockPortSchema(
                    plan.descriptor, new OnePortSchema(body.outputType()));
            BindBlockPort block = new BindBlockPort(
                    schema,
                    context,
                    plan.occurrence,
                    OnePort.invocation(bodyContext, body));
            OperatorDeclaration declaration = OperatorDeclaration.monomorphic(
                    "ALLOY/LOCAL-BIND/" + source.getOpcode(),
                    Collections.singletonList(schema),
                    outputType(source),
                    Collections.emptyMap(),
                    null);
            return insert(TypedENode.construct(
                    declaration.instantiateMonomorphic(),
                    context,
                    Collections.singletonList(block)));
        }

        private BinderPlan binderPlan(List<QuantiVar> variables, TypedSlotContext ambient) {
            List<BindingPayload> payloads = new ArrayList<>(variables.size());
            int exchangeClass = -1;
            QuantiVar previous = null;
            for (QuantiVar variable : variables) {
                if (previous == null || !sameExchangeRun(previous, variable)) {
                    exchangeClass++;
                }
                payloads.add(new BindingPayload(
                        variable.getQuantifier().name(),
                        variable.getCardinality().name(),
                        variable.getDisjointnessClass() > 0
                                ? variable.getDisjointnessClass()
                                : BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS,
                        StructuralKey.of(
                                "alloy-binder-domain",
                                List.of(requireTypeName(
                                        variable.getCarrierTypeName(),
                                        "matrix binder carrier")),
                                Collections.emptyList()),
                        bindingType(variable),
                        exchangeClass));
                previous = variable;
            }
            return binderPlan(variables, payloads, ambient, true);
        }

        private static boolean sameExchangeRun(QuantiVar left, QuantiVar right) {
            if (left.getQuantifier() != right.getQuantifier()) {
                return false;
            }
            if (left.getQuantifier() == QuantiVar.Quantifier.ALL
                    || left.getQuantifier() == QuantiVar.Quantifier.SOME) {
                return true;
            }
            return left.getBindingPath().equals(right.getBindingPath());
        }

        private BinderPlan localBinderPlan(
                Opcode binderOpcode,
                List<LocalCoordinate> locals,
                TypedSlotContext ambient) {
            List<QuantiVar> variables = new ArrayList<>(locals.size());
            List<BindingPayload> payloads = new ArrayList<>(locals.size());
            for (int index = 0; index < locals.size(); index++) {
                LocalCoordinate local = locals.get(index);
                // A comprehension coordinate is also an ordered result column.
                int exchangeClass = binderOpcode == Opcode.COMPREHENSION ? index : 0;
                QuantiVar placeholder = new QuantiVar(index, local.name, "", local.type.toString());
                variables.add(placeholder);
                payloads.add(new BindingPayload(
                        local.quantifier,
                        local.multiplicity,
                        local.disjointnessClass,
                        local.domain,
                        local.type,
                        exchangeClass));
            }
            return binderPlan(variables, payloads, ambient, false);
        }

        private BinderPlan binderPlan(
                List<QuantiVar> variables,
                List<BindingPayload> payloads,
                TypedSlotContext ambient,
                boolean canonicalizeIndependentOrder) {
            if (variables.size() != payloads.size()) {
                throw new IllegalArgumentException(
                        "Binder variables and payloads must have equal arity");
            }
            List<Integer> order = new ArrayList<>(variables.size());
            for (int index = 0; index < variables.size(); index++) {
                order.add(index);
            }
            if (canonicalizeIndependentOrder) {
                order.sort((left, right) -> compareBindingPayloads(
                        payloads.get(left), payloads.get(right), left, right));
            }
            List<QuantiVar> orderedVariables = new ArrayList<>(variables.size());
            List<BindingPayload> orderedPayloads = new ArrayList<>(payloads.size());
            List<Integer> sourceToCoordinate = new ArrayList<>(
                    Collections.nCopies(variables.size(), -1));
            for (int coordinate = 0; coordinate < order.size(); coordinate++) {
                int source = order.get(coordinate);
                orderedVariables.add(variables.get(source));
                orderedPayloads.add(payloads.get(source));
                sourceToCoordinate.set(source, coordinate);
            }

            Map<GraphType, Integer> ordinals = new TreeMap<>();
            List<BinderCoordinateDescriptor> coordinates = new ArrayList<>(variables.size());
            for (BindingPayload payload : orderedPayloads) {
                int ordinal = ordinals.getOrDefault(payload.type, 0);
                ordinals.put(payload.type, ordinal + 1);
                coordinates.add(new BinderCoordinateDescriptor(
                        TypedSlot.canonicalBound(payload.type, ordinal),
                        payload.domain,
                        payload.quantifier,
                        payload.multiplicity,
                        payload.disjointnessClass,
                        payload.exchangeClass,
                        TypedSlotContext.empty()));
            }
            TypedSlotContext descriptorContext = TypedSlotContext.of(
                    coordinates.stream()
                            .map(BinderCoordinateDescriptor::canonicalSlot)
                            .toList());
            List<BinderAutomorphismCertificate> certificates = new ArrayList<>();
            int certificateOrdinal = 0;
            Map<BindingPayload, Integer> previousByPayload = new LinkedHashMap<>();
            for (int index = 0; index < orderedPayloads.size(); index++) {
                Integer previousIndex = previousByPayload.put(
                        orderedPayloads.get(index), index);
                if (previousIndex == null) {
                    continue;
                }
                Map<TypedSlot, TypedSlot> swap = new LinkedHashMap<>();
                for (TypedSlot slot : descriptorContext) {
                    swap.put(slot, slot);
                }
                TypedSlot left = coordinates.get(previousIndex).canonicalSlot();
                TypedSlot right = coordinates.get(index).canonicalSlot();
                swap.put(left, right);
                swap.put(right, left);
                TypedPermutation permutation = TypedPermutation.of(descriptorContext, swap);
                certificates.add(new BinderAutomorphismCertificate(
                        coordinates,
                        permutation,
                        CertificateOrigin.binderAutomorphism(
                                SIGNATURE_VERSION,
                                "alloy-binder-block",
                                certificateOrdinal++)));
            }
            BinderBlockDescriptor descriptor = BinderBlockDescriptor.certified(
                    coordinates, certificates);
            return new BinderPlan(
                    orderedVariables,
                    coordinates,
                    descriptor,
                    descriptor.freshOccurrenceRenaming(ambient),
                    sourceToCoordinate);
        }

        private static int compareBindingPayloads(
                BindingPayload left,
                BindingPayload right,
                int leftSource,
                int rightSource) {
            int comparison = Integer.compare(left.exchangeClass, right.exchangeClass);
            if (comparison != 0) {
                return comparison;
            }
            comparison = left.type.compareTo(right.type);
            if (comparison != 0) {
                return comparison;
            }
            comparison = left.domain.compareTo(right.domain);
            if (comparison != 0) {
                return comparison;
            }
            comparison = left.quantifier.compareTo(right.quantifier);
            if (comparison != 0) {
                return comparison;
            }
            comparison = left.multiplicity.compareTo(right.multiplicity);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(
                    left.disjointnessClass, right.disjointnessClass);
            return comparison != 0
                    ? comparison
                    : Integer.compare(leftSource, rightSource);
        }

        private TypedInvocation asInvocation(
                OnePort value,
                TypedSlotContext context,
                String label) {
            if (value.leaf() instanceof is.fivefivefive.CanDis.theory.InvocationPortLeaf) {
                return ((is.fivefivefive.CanDis.theory.InvocationPortLeaf) value.leaf()).invocation();
            }
            return insert(fixedNode(
                    "ALLOY/VALUE/" + label,
                    value.schema().type(),
                    context,
                    Collections.singletonList(value)));
        }

        private TypedENode fixedNode(
                String head,
                GraphType output,
                TypedSlotContext context,
                List<OnePort> operands) {
            List<PortSchema> schemas = new ArrayList<>(operands.size());
            List<PortValue> ports = new ArrayList<>(operands.size());
            for (OnePort operand : operands) {
                schemas.add(operand.schema());
                ports.add(operand);
            }
            OperatorDeclaration declaration = OperatorDeclaration.monomorphic(
                    head, schemas, output, Collections.emptyMap(), null);
            return TypedENode.construct(
                    declaration.instantiateMonomorphic(), context, ports);
        }

        private TypedENode constantNode(
                String value,
                GraphType output,
                TypedSlotContext context) {
            OperatorDeclaration declaration = OperatorDeclaration.monomorphic(
                    "ALLOY/CONSTANT/" + value,
                    Collections.emptyList(),
                    output,
                    Collections.emptyMap(),
                    null);
            return TypedENode.construct(
                    declaration.instantiateMonomorphic(), context, Collections.emptyList());
        }

        private TypedInvocation insert(TypedENode node) {
            TypedSlotContext callerContext = node.context();
            TypedENode exact = node.inExactSupportContext();
            ensureQuiescent();
            CertifiedInsertionResult insertion = graph.insertNode(
                    exact, graph.coherentWitnessFamily());
            return finishInsertion(callerContext, insertion);
        }

        private TypedInvocation insert(CertifiedFlatConstruction construction) {
            if (construction.collapsedToSingleton()) {
                throw new IllegalArgumentException(
                        "A singleton flat construction is returned directly, not inserted");
            }
            TypedSlotContext callerContext = construction.node().context();
            ensureQuiescent();
            CertifiedInsertionResult insertion = graph.insertNodeConstructed(
                    construction, graph.coherentWitnessFamily());
            return finishInsertion(callerContext, insertion);
        }

        private TypedInvocation insert(NodeConstruction construction) {
            if (construction.containerConstruction != null) {
                TypedSlotContext callerContext =
                        construction.containerConstruction.node().context();
                ensureQuiescent();
                CertifiedInsertionResult insertion = graph.insertNodeConstructed(
                        construction.containerConstruction,
                        graph.coherentWitnessFamily());
                return finishInsertion(callerContext, insertion);
            }
            return insert(construction.node);
        }

        private TypedInvocation finishInsertion(
                TypedSlotContext callerContext,
                CertifiedInsertionResult insertion) {
            ensureQuiescent();
            TypedInvocation normalized = graph.findWithProvenance(
                    insertion.returnedInvocation()).leaderInvocation();
            if (!normalized.callerContext().equals(callerContext)) {
                normalized = normalized.act(TypedEmbedding.inclusion(
                        normalized.callerContext(), callerContext));
            }
            return normalized;
        }

        private static final class NodeConstruction {
            private final TypedENode node;
            private final CertifiedContainerConstruction containerConstruction;

            private NodeConstruction(
                    TypedENode node,
                    CertifiedContainerConstruction containerConstruction) {
                this.node = Objects.requireNonNull(node, "node");
                this.containerConstruction = containerConstruction;
            }

            private static NodeConstruction plain(TypedENode node) {
                return new NodeConstruction(node, null);
            }

            private static NodeConstruction certified(
                    CertifiedContainerConstruction construction) {
                Objects.requireNonNull(construction, "construction");
                return new NodeConstruction(construction.node(), construction);
            }
        }

        private void ensureQuiescent() {
            if (graph.status() == GraphStatus.DIRTY) {
                graph.rebuild();
                rebuilds++;
            }
            if (graph.status() != GraphStatus.QUIESCENT) {
                throw new IllegalStateException(
                        "Exact Alloy graph did not reach quiescence: " + graph.status());
            }
        }
    }

    private static void requireProfileConsistency(
            List<NormalForm> normalForms,
            SemanticProfile expected) {
        Set<EGraphNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
        for (NormalForm form : normalForms) {
            if (form.getCertificationMatrixEGraph() != null) {
                pending.add(form.getCertificationMatrixEGraph());
            }
        }
        while (!pending.isEmpty()) {
            EGraphNode node = pending.removeFirst();
            if (!visited.add(node)) {
                continue;
            }
            if (!expected.equals(node.getSemanticProfile())) {
                throw new IllegalStateException(
                        "Cross-profile Alloy e-node in one adaptation: "
                                + node.getOpcode());
            }
            pending.addAll(node.getChildren());
        }
    }

    private static PortSchema containerSchema(
            FlexibleArityKind kind,
            OnePortSchema element) {
        switch (kind) {
            case SET:
                return new SetPortSchema(ContainerEmptiness.K_PLUS, element);
            case BAG:
                return new BagPortSchema(ContainerEmptiness.K_PLUS, element);
            case SEQUENCE:
                return new SeqPortSchema(ContainerEmptiness.K_PLUS, element);
            default:
                throw new IllegalArgumentException("FIXED is not a flexible port kind");
        }
    }

    private static ContainerLawDeclaration certifiedLaws(
            PortSchema schema,
            SemanticProfile semanticProfile,
            Opcode opcode,
            String operator,
            GraphType resultType,
            boolean associative) {
        List<ContainerLawCertificate> certificates = new ArrayList<>();
        if (associative) {
            certificates.add(AlloyLawRegistry.issue(
                    semanticProfile,
                    opcode,
                    operator,
                    resultType,
                    PortPath.at(0),
                    schema,
                    ContainerLawCertificate.Law.ASSOCIATIVITY));
        }
        if (schema instanceof BagPortSchema || schema instanceof SetPortSchema) {
            certificates.add(AlloyLawRegistry.issue(
                    semanticProfile,
                    opcode,
                    operator,
                    resultType,
                    PortPath.at(0),
                    schema,
                    ContainerLawCertificate.Law.COMMUTATIVITY));
        }
        if (schema instanceof SetPortSchema) {
            certificates.add(AlloyLawRegistry.issue(
                    semanticProfile,
                    opcode,
                    operator,
                    resultType,
                    PortPath.at(0),
                    schema,
                    ContainerLawCertificate.Law.IDEMPOTENCY));
        }
        return ContainerLawDeclaration.certified(schema, certificates);
    }

    private static GraphType outputType(EGraphNode node) {
        if (node.getMetatype() == Metatype.BOOLEAN) {
            if (node.getExactAlloyType() != null
                    && node.getExactAlloyType().kind()
                            != is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.BOOL
                    && node.getExactAlloyType().kind()
                            != is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.UNKNOWN) {
                throw new IllegalStateException(
                        "Boolean IR node carries a non-Boolean exact Alloy type");
            }
            return GraphType.BOOL;
        }
        if (node.getOpcode() == Opcode.CARDINALITY
                || node.getOpcode() == Opcode.CAST2INT) {
            return GraphType.INT;
        }
        if (node.getExactAlloyType() != null) {
            return AlloyTypeBridge.graphType(node.getExactAlloyType());
        }
        throw new IllegalStateException(
                "Theory-faithful adaptation requires an exact occurrence type for "
                        + node.getOpcode());
    }

    private static GraphType bindingType(QuantiVar variable) {
        ExactAlloyType exact = variable.getExactAlloyType();
        if (exact != null
                && exact.kind() == ExactAlloyType.Kind.RELATION
                && exact.relationArity() > 1) {
            return AlloyTypeBridge.graphType(exact);
        }
        String type = requireTypeName(variable.getTypeName(), "quantified binding");
        return bindingType(type);
    }

    private static GraphType bindingType(EGraphNode variable) {
        ExactAlloyType exact = variable.getExactAlloyType();
        if (exact != null
                && exact.kind() == ExactAlloyType.Kind.RELATION
                && exact.relationArity() > 1) {
            return AlloyTypeBridge.graphType(exact);
        }
        return bindingType(requireTypeName(variable.getSourceType(), "local binding"));
    }

    private static GraphType bindingType(String type) {
        String normalized = type.startsWith("VAR_") ? type.substring(4) : type;
        if ("int".equalsIgnoreCase(normalized)) {
            return GraphType.INT;
        }
        return GraphType.constructor(
                "AlloyCarrier",
                Collections.singletonList(GraphType.constructor(normalized)));
    }

    private static String semanticHead(EGraphNode node) {
        StringBuilder head = new StringBuilder("ALLOY/").append(node.getOpcode());
        switch (node.getOpcode()) {
            case CONSTANT:
                head.append('/').append(normalizeAtom(node.getSourceName()));
                break;
            case GLOBALBINDING:
                head.append('/').append(node.getSemanticIdentity() == null
                        ? normalizeAtom(node.getSourceName())
                        : node.getSemanticIdentity());
                break;
            case CALL:
                CallMetadata.Validated call = CallMetadata.require(node);
                head.append('/').append(call.identity());
                head.append('/').append(call.arity());
                head.append('/').append(call.kind());
                head.append('/').append(call.authority().name());
                break;
            case REF:
            case SHADOW:
                head.append('/').append(normalizeAtom(node.getSourceName()));
                break;
            default:
                break;
        }
        return head.toString();
    }

    private static String normalizeAtom(String value) {
        return value == null ? "" : value.replace("this/", "").replaceAll("\\s+", "").trim();
    }

    private static String normalizeType(String value) {
        return normalizeAtom(value);
    }

    private static String requireTypeName(String value, String label) {
        requireAdmittedTypeName(value, label);
        String normalized = normalizeType(value);
        if (normalized.isEmpty()) {
            throw new IllegalStateException(label + " has no source type provenance");
        }
        return normalized;
    }

    private static void requireAdmittedTypeName(String value, String label) {
        if (!AlloyTypeBridge.isAdmittedIdentity(value)) {
            throw new IllegalStateException(
                    label + " must have a well-formed visible type identity");
        }
    }

    private static void registerBinding(
            Map<String, TypedSlot> bindings,
            QuantiVar variable,
            TypedSlot slot) {
        putBinding(bindings, variable.getName(), slot);
        for (String alias : variable.getOriginalNames()) {
            putBinding(bindings, alias, slot);
        }
        putBinding(bindings, variable.getDeBruijnKey(), slot);
    }

    private static void putBinding(
            Map<String, TypedSlot> bindings,
            String name,
            TypedSlot slot) {
        if (name == null || name.isEmpty()) {
            return;
        }
        bindings.put(name, slot);
    }

    private static boolean hasBinding(
            Map<String, TypedSlot> bindings,
            QuantiVar variable) {
        if (bindings.containsKey(variable.getName())
                || bindings.containsKey(variable.getDeBruijnKey())) {
            return true;
        }
        for (String alias : variable.getOriginalNames()) {
            if (bindings.containsKey(alias)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> identitySlotNames(EGraphNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String slot : node.getEClass().getSlots()) {
            result.put(slot, slot);
        }
        return result;
    }

    private static Map<String, String> composeSlotNames(
            Map<String, String> childToParent,
            Map<String, String> parentToAmbient) {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(childToParent.keySet());
        Collections.sort(keys);
        for (String child : keys) {
            String parent = childToParent.get(child);
            result.put(child, parentToAmbient.getOrDefault(parent, parent));
        }
        return result;
    }

    private static boolean isLocalBinder(EGraphNode node) {
        if (node.getOpcode() != Opcode.SUM && node.getOpcode() != Opcode.COMPREHENSION
                && node.getOpcode() != Opcode.FORALL && node.getOpcode() != Opcode.EXISTS
                && node.getOpcode() != Opcode.NO && node.getOpcode() != Opcode.ONE
                && node.getOpcode() != Opcode.LONE) {
            return false;
        }
        for (EGraphNode child : node.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRelDecl(Opcode opcode) {
        return opcode == Opcode.GENERICRELDECL || opcode == Opcode.DISJ
                || opcode == Opcode.VAR || opcode == Opcode.DISJVAR;
    }

    private static boolean isDisjointDecl(Opcode opcode) {
        return opcode == Opcode.DISJ || opcode == Opcode.DISJVAR;
    }

    private static StructuralKey domainKey(
            EGraphNode domain,
            Map<String, String> names,
            List<LocalCoordinate> preceding) {
        if (domain == null) {
            throw new IllegalArgumentException(
                    "A missing local declaration domain cannot be interpreted as univ");
        }
        if (domain.getOpcode() == Opcode.VARIABLE) {
            String local = firstNonempty(domain.getAlphaName(), domain.getSourceName());
            String resolved = names.getOrDefault(local, local);
            for (int index = 0; index < preceding.size(); index++) {
                if (preceding.get(index).name.equals(resolved)) {
                    return StructuralKey.leaf("alloy-local-domain-bound", Integer.toString(index));
                }
            }
            return StructuralKey.leaf("alloy-local-domain-free", normalizeAtom(resolved));
        }
        List<StructuralKey> children = new ArrayList<>();
        for (EGraphNode child : domain.getChildren()) {
            children.add(domainKey(child, names, preceding));
        }
        if (domain.isOrderInsensitive()) {
            children.sort(Comparator.naturalOrder());
        }
        return StructuralKey.of(
                "alloy-local-domain-node",
                List.of(semanticHead(domain), normalizeAtom(domain.getSourceName())),
                children);
    }

    private static String domainMultiplicity(EGraphNode domain) {
        if (domain == null) {
            throw new IllegalArgumentException(
                    "A missing local declaration domain has no multiplicity");
        }
        switch (domain.getOpcode()) {
            case SOME:
                return QuantiVar.Cardinality.SOME.name();
            case ONE:
                return QuantiVar.Cardinality.ONE.name();
            case LONE:
                return QuantiVar.Cardinality.LONE.name();
            case EXACTLY:
                return QuantiVar.Cardinality.EXACTLY.name();
            default:
                return QuantiVar.Cardinality.SET.name();
        }
    }

    private static boolean isBinaryLeft(TemporalOp operation) {
        return operation == TemporalOp.UNTILL || operation == TemporalOp.RELEASESL
                || operation == TemporalOp.SINCEL || operation == TemporalOp.TRIGGEREDL;
    }

    private static boolean isMatchingBinaryRight(TemporalOp left, TemporalOp right) {
        return left == TemporalOp.UNTILL && right == TemporalOp.UNTILR
                || left == TemporalOp.RELEASESL && right == TemporalOp.RELEASESR
                || left == TemporalOp.SINCEL && right == TemporalOp.SINCER
                || left == TemporalOp.TRIGGEREDL && right == TemporalOp.TRIGGEREDR;
    }

    private static String binaryBase(TemporalOp operation) {
        switch (operation) {
            case UNTILL:
                return "UNTIL";
            case RELEASESL:
                return "RELEASES";
            case SINCEL:
                return "SINCE";
            case TRIGGEREDL:
                return "TRIGGERED";
            default:
                throw new IllegalArgumentException("Not a binary-left temporal phase: " + operation);
        }
    }

    private static String temporalReference(int index, int arity) {
        return "temporal[" + index + ":" + arity + "]";
    }

    private static String firstNonempty(String first, String second) {
        return first != null && !first.isEmpty() ? first : second;
    }

    private static final class BinderPlan {
        private final List<QuantiVar> variables;
        private final List<BinderCoordinateDescriptor> coordinates;
        private final BinderBlockDescriptor descriptor;
        private final TypedRenaming occurrence;
        private final List<Integer> sourceToCoordinate;

        private BinderPlan(
                List<QuantiVar> variables,
                List<BinderCoordinateDescriptor> coordinates,
                BinderBlockDescriptor descriptor,
                TypedRenaming occurrence,
                List<Integer> sourceToCoordinate) {
            this.variables = Collections.unmodifiableList(new ArrayList<>(variables));
            this.coordinates = Collections.unmodifiableList(new ArrayList<>(coordinates));
            this.descriptor = descriptor;
            this.occurrence = occurrence;
            this.sourceToCoordinate = Collections.unmodifiableList(
                    new ArrayList<>(sourceToCoordinate));
        }
    }

    private static final class BindingPayload {
        private final String quantifier;
        private final String multiplicity;
        private final int disjointnessClass;
        private final StructuralKey domain;
        private final GraphType type;
        private final int exchangeClass;

        private BindingPayload(
                String quantifier,
                String multiplicity,
                int disjointnessClass,
                StructuralKey domain,
                GraphType type,
                int exchangeClass) {
            this.quantifier = quantifier;
            this.multiplicity = multiplicity;
            this.disjointnessClass = disjointnessClass;
            this.domain = domain;
            this.type = type;
            this.exchangeClass = exchangeClass;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BindingPayload)) {
                return false;
            }
            BindingPayload payload = (BindingPayload) other;
            return quantifier.equals(payload.quantifier)
                    && multiplicity.equals(payload.multiplicity)
                    && disjointnessClass == payload.disjointnessClass
                    && exchangeClass == payload.exchangeClass
                    && domain.equals(payload.domain)
                    && type.equals(payload.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    quantifier, multiplicity, disjointnessClass,
                    domain, type, exchangeClass);
        }
    }

    private static final class LocalCoordinate {
        private final String name;
        private final StructuralKey domain;
        private final String quantifier;
        private final String multiplicity;
        private final int disjointnessClass;
        private final GraphType type;

        private LocalCoordinate(
                String name,
                StructuralKey domain,
                String quantifier,
                String multiplicity,
                int disjointnessClass,
                GraphType type) {
            this.name = name;
            this.domain = domain;
            this.quantifier = quantifier;
            this.multiplicity = multiplicity;
            this.disjointnessClass = disjointnessClass;
            this.type = type;
        }
    }

    private static final class LocalBinderFrame {
        private final NormalForm ownerPhase;
        private final EGraphNode source;
        private final BinderBlockDescriptor descriptor;
        private final Map<String, Integer> sourceCoordinates;
        private final Map<Integer, TypedSlot> slotsByCoordinate;

        private LocalBinderFrame(
                NormalForm ownerPhase,
                EGraphNode source,
                BinderBlockDescriptor descriptor,
                Map<String, Integer> sourceCoordinates,
                Map<Integer, TypedSlot> slotsByCoordinate) {
            this.ownerPhase = Objects.requireNonNull(ownerPhase, "local owner phase");
            this.source = Objects.requireNonNull(source, "local binder source");
            this.descriptor = Objects.requireNonNull(descriptor, "local binder descriptor");
            this.sourceCoordinates = Collections.unmodifiableMap(
                    new LinkedHashMap<>(sourceCoordinates));
            this.slotsByCoordinate = Collections.unmodifiableMap(
                    new LinkedHashMap<>(slotsByCoordinate));
        }
    }

    private static final class InvocationKey {
        private final int eclass;
        private final Map<String, String> slots;
        private final TypedSlotContext context;
        private final String head;

        private InvocationKey(
                int eclass,
                Map<String, String> slots,
                TypedSlotContext context,
                String head) {
            this.eclass = eclass;
            this.slots = Collections.unmodifiableMap(new TreeMap<>(slots));
            this.context = context;
            this.head = head;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof InvocationKey)) {
                return false;
            }
            InvocationKey key = (InvocationKey) other;
            return eclass == key.eclass
                    && slots.equals(key.slots)
                    && context.equals(key.context)
                    && head.equals(key.head);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eclass, slots, context, head);
        }
    }

    private static final class RelationalCoercionKey {
        private final OnePort operand;
        private final GraphType targetType;

        private RelationalCoercionKey(OnePort operand, GraphType targetType) {
            this.operand = Objects.requireNonNull(operand, "operand");
            this.targetType = Objects.requireNonNull(targetType, "targetType");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RelationalCoercionKey
                    && operand.equals(((RelationalCoercionKey) other).operand)
                    && targetType.equals(((RelationalCoercionKey) other).targetType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operand, targetType);
        }
    }
}
