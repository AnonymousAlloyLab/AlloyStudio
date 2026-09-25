package is.fivefivefive.CanDis.metric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import is.fivefivefive.CanDis.core.CallMetadata;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.NormalForm.TemporalOp;
import is.fivefivefive.CanDis.core.QuantiVar;
import is.fivefivefive.CanDis.canonical.CanonicalObservation;
import is.fivefivefive.CanDis.metric.RepairView.Binding;
import is.fivefivefive.CanDis.metric.RepairView.BindingRole;
import is.fivefivefive.CanDis.metric.RepairView.ContainerKind;
import is.fivefivefive.CanDis.metric.RepairView.Declaration;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import is.fivefivefive.CanDis.metric.RepairView.Phase;
import is.fivefivefive.CanDis.metric.RepairView.TemporalNode;
import is.fivefivefive.CanDis.theory.BinderBlockDescriptor;
import is.fivefivefive.CanDis.theory.BinderCoordinateDescriptor;
import is.fivefivefive.CanDis.theory.BagPortSchema;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.CertifiedSemanticArtifact;
import is.fivefivefive.CanDis.theory.ContainerLawCertificate;
import is.fivefivefive.CanDis.theory.ContainerLawDeclaration;
import is.fivefivefive.CanDis.theory.DependentChainCertificate;
import is.fivefivefive.CanDis.theory.DependentChainKind;
import is.fivefivefive.CanDis.theory.DependentChainTheory;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.OnePortSchema;
import is.fivefivefive.CanDis.theory.PortPath;
import is.fivefivefive.CanDis.theory.PortSchema;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.SeqPortSchema;
import is.fivefivefive.CanDis.theory.SetPortSchema;
import is.fivefivefive.CanDis.theory.SiblingQuotient;
import is.fivefivefive.CanDis.theory.StructuralKey;
import is.fivefivefive.CanDis.theory.TypedPermutation;
import is.fivefivefive.CanDis.theory.TypedSlot;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter.CertifiedSetOperandPartition;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter.DependentChainSourceBinding;

/**
 * Re-expresses the established repaired-NormalForm metric domain and attaches
 * scope/container legality obtained from the faithful certified artifact.
 */
public final class RepairProjection {
    public static final String VERSION = "faithful-fast-rewrite-repair-projection-v13";

    private RepairProjection() {
    }

    static GraphType requireExactResultType(EGraphNode source) {
        Objects.requireNonNull(source, "source");
        if (source.getMetatype() == EGraphNode.Metatype.BOOLEAN) {
            return GraphType.BOOL;
        }
        if (source.getOpcode() == Opcode.CARDINALITY
                || source.getOpcode() == Opcode.CAST2INT) {
            return GraphType.INT;
        }
        if (source.getExactAlloyType() == null) {
            throw new IllegalStateException(
                    "Repair projection requires an exact relational occurrence type for "
                            + source.getOpcode());
        }
        return AlloyTypeBridge.graphType(source.getExactAlloyType());
    }

    public static RepairView project(
            TheoryAlloyAdapter.Result evidence,
            List<NormalForm> normalForms) {
        return RepairView.fromCertifiedProjection(
                projectComponents(evidence, normalForms));
    }

    static Projection projectComponents(
            TheoryAlloyAdapter.Result evidence,
            List<NormalForm> normalForms) {
        Objects.requireNonNull(evidence, "evidence");
        evidence.requireRepairProjectionSources(normalForms);
        CertifiedSemanticArtifact artifact = evidence.semanticArtifact();
        List<BinderBlockDescriptor> phaseDescriptors =
                evidence.phaseBinderDescriptors();
        List<? extends List<Integer>> phaseSourceCoordinates =
                evidence.phaseSourceCoordinates();
        Map<EGraphNode, BinderBlockDescriptor> localBinderDescriptors =
                evidence.localBinderDescriptors();
        Map<EGraphNode, Map<String, Integer>> localBinderSourceCoordinates =
                evidence.localBinderSourceCoordinates();
        Map<NormalForm, List<TheoryAlloyAdapter.PhaseLocalBindingCertificate>>
                phaseLocalBindingCertificates =
                        evidence.phaseLocalBindingCertificates();
        Map<EGraphNode, DependentChainSourceBinding> dependentChainSourceBindings =
                evidence.dependentChainSourceBindings();
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(normalForms, "normalForms");
        Objects.requireNonNull(phaseDescriptors, "phaseDescriptors");
        Objects.requireNonNull(phaseSourceCoordinates, "phaseSourceCoordinates");
        Objects.requireNonNull(localBinderDescriptors, "localBinderDescriptors");
        Objects.requireNonNull(
                localBinderSourceCoordinates, "localBinderSourceCoordinates");
        Objects.requireNonNull(
                phaseLocalBindingCertificates, "phaseLocalBindingCertificates");
        Objects.requireNonNull(
                dependentChainSourceBindings, "dependentChainSourceBindings");
        if (normalForms.size() != phaseDescriptors.size()
                || normalForms.size() != phaseSourceCoordinates.size()) {
            throw new IllegalArgumentException(
                    "Every repaired normal-form phase requires one certified binder plan");
        }

        CertifiedContainers containers = CertifiedContainers.from(
                artifact, evidence.certifiedSetOperandPartitions());
        CertifiedDependentChains chains = CertifiedDependentChains.from(
                artifact, dependentChainSourceBindings);
        OriginIndex origins = new OriginIndex(
                normalForms, phaseDescriptors, phaseSourceCoordinates);
        List<Phase> phases = new ArrayList<>(normalForms.size());
        for (int phaseIndex = 0; phaseIndex < normalForms.size(); phaseIndex++) {
            phases.add(projectPhase(
                    normalForms.get(phaseIndex),
                    phaseIndex,
                    phaseDescriptors,
                    phaseSourceCoordinates,
                    localBinderDescriptors,
                    localBinderSourceCoordinates,
                    phaseLocalBindingCertificates,
                    origins,
                    containers,
                    chains));
        }
        CanonicalPhaseProjection canonical = CanonicalPhaseProjection.create(
                normalForms, phases);
        return new Projection(
                canonical.temporalRoot,
                canonical.phases,
                artifact.semanticProfile(),
                evidence.canonicalKey());
    }

    static final class Projection {
        final TemporalNode temporalRoot;
        final List<Phase> phases;
        final SemanticProfile semanticProfile;
        final StructuralKey producerObservationKey;

        private Projection(
                TemporalNode temporalRoot,
                List<Phase> phases,
                SemanticProfile semanticProfile,
                StructuralKey producerObservationKey) {
            this.temporalRoot = Objects.requireNonNull(temporalRoot, "temporalRoot");
            this.phases = List.copyOf(phases);
            this.semanticProfile = Objects.requireNonNull(
                    semanticProfile, "semanticProfile");
            this.producerObservationKey = Objects.requireNonNull(
                    producerObservationKey, "producerObservationKey");
        }
    }

    private static Phase projectPhase(
            NormalForm normalForm,
            int phaseIndex,
            List<BinderBlockDescriptor> descriptors,
            List<? extends List<Integer>> sourceCoordinates,
            Map<EGraphNode, BinderBlockDescriptor> localBinderDescriptors,
            Map<EGraphNode, Map<String, Integer>> localBinderSourceCoordinates,
            Map<NormalForm, List<TheoryAlloyAdapter.PhaseLocalBindingCertificate>>
                    phaseLocalBindingCertificates,
            OriginIndex origins,
            CertifiedContainers containers,
            CertifiedDependentChains chains) {
        List<Binding> bindings = new ArrayList<>();
        Map<String, Integer> aliases = new LinkedHashMap<>();
        Map<String, GraphType> aliasTypes = new LinkedHashMap<>();

        List<QuantiVar> parameters = normalForm.getParams();
        for (int index = 0; index < parameters.size(); index++) {
            QuantiVar variable = parameters.get(index);
            Declaration declaration = sourceDeclaration(
                    variable,
                    "parameter:" + requireTypeName(
                            variable.getCarrierTypeName(), "parameter carrier"),
                    -1,
                    Collections.emptyList());
            addBinding(
                    bindings,
                    aliases,
                    variable,
                    new Binding(
                            BindingRole.PARAMETER,
                            index,
                            -1,
                            -1,
                            declaration,
                            variable.getBindingPath(),
                            Collections.emptyList(),
                            false));
            registerAliasType(
                    aliasTypes,
                    variable,
                    bindingType(variable));
        }

        BinderBlockDescriptor descriptor = descriptors.get(phaseIndex);
        List<Integer> coordinateMap = sourceCoordinates.get(phaseIndex);
        List<QuantiVar> quantified = normalForm.getMatrixQuantiVars();
        requireDescriptorMatches(quantified, descriptor, coordinateMap, phaseIndex);
        List<Declaration> quantifiers = new ArrayList<>(quantified.size());
        for (int index = 0; index < quantified.size(); index++) {
            QuantiVar variable = quantified.get(index);
            int coordinate = coordinateMap.get(index);
            Declaration declaration = certifiedDeclaration(
                    variable, descriptor, coordinate);
            quantifiers.add(declaration);
            addBinding(
                    bindings,
                    aliases,
                    variable,
                    new Binding(
                            BindingRole.MATRIX,
                            index,
                            phaseIndex,
                            coordinate,
                            declaration,
                            variable.getBindingPath(),
                            certifiedOrbit(descriptor, coordinate),
                            true));
            registerAliasType(
                    aliasTypes,
                    variable,
                    descriptor.coordinates().get(coordinate).canonicalSlot().type());
        }

        List<QuantiVar> inherited = normalForm.getInheritedQuantiVars();
        for (int index = 0; index < inherited.size(); index++) {
            QuantiVar variable = inherited.get(index);
            Origin origin = origins.find(variable);
            Declaration declaration = certifiedDeclaration(
                    variable, descriptors.get(origin.phase), origin.coordinate);
            addBinding(
                    bindings,
                    aliases,
                    variable,
                    new Binding(
                            BindingRole.INHERITED,
                            index,
                            origin.phase,
                            origin.coordinate,
                            declaration,
                            variable.getBindingPath(),
                            certifiedOrbit(descriptors.get(origin.phase), origin.coordinate),
                            true));
            registerAliasType(
                    aliasTypes,
                    variable,
                    descriptors.get(origin.phase).coordinates().get(origin.coordinate)
                            .canonicalSlot().type());
        }

        List<TheoryAlloyAdapter.PhaseLocalBindingCertificate> localImports =
                phaseLocalBindingCertificates.getOrDefault(
                        normalForm, Collections.emptyList());
        if (localImports.size() != normalForm.getPhaseLocalBindingImports().size()) {
            throw new IllegalStateException(
                    "A temporal phase-local import lacks an exact adapter certificate");
        }
        for (int index = 0; index < localImports.size(); index++) {
            TheoryAlloyAdapter.PhaseLocalBindingCertificate certificate =
                    localImports.get(index);
            QuantiVar variable = certificate.source().variable();
            Declaration declaration = certifiedDeclaration(
                    variable,
                    certificate.ownerDescriptor(),
                    certificate.coordinate());
            addBinding(
                    bindings,
                    aliases,
                    variable,
                    new Binding(
                            BindingRole.LOCAL_INHERITED,
                            index,
                            certificate.ownerPhase(),
                            certificate.coordinate(),
                            declaration,
                            variable.getBindingPath(),
                            certificate.ownerContext(),
                            certifiedOrbit(
                                    certificate.ownerDescriptor(),
                                    certificate.coordinate()),
                            false));
            registerAliasType(
                    aliasTypes,
                    variable,
                    certificate.ownerDescriptor().coordinates()
                            .get(certificate.coordinate()).canonicalSlot().type());
        }

        Node matrix = projectNode(
                normalForm.getMatrixEGraph(),
                aliases,
                aliasTypes,
                Collections.emptyMap(),
                Collections.emptyMap(),
                0,
                localBinderDescriptors,
                localBinderSourceCoordinates,
                containers,
                chains);
        return new Phase(quantifiers, bindings, matrix);
    }

    private static void addBinding(
            List<Binding> bindings,
            Map<String, Integer> aliases,
            QuantiVar variable,
            Binding binding) {
        int index = bindings.size();
        bindings.add(binding);
        putAlias(aliases, variable.getName(), index);
        putAlias(aliases, variable.getDeBruijnKey(), index);
    }

    private static void putAlias(Map<String, Integer> aliases, String name, int index) {
        if (name != null && !name.isEmpty()) {
            aliases.put(name, index);
        }
    }

    private static void registerAliasType(
            Map<String, GraphType> aliases,
            QuantiVar variable,
            GraphType type) {
        putAliasType(aliases, variable.getName(), type);
        putAliasType(aliases, variable.getDeBruijnKey(), type);
    }

    private static void putAliasType(
            Map<String, GraphType> aliases,
            String name,
            GraphType type) {
        if (name == null || name.isEmpty()) {
            return;
        }
        GraphType previous = aliases.putIfAbsent(name, type);
        if (previous != null && !previous.equals(type)) {
            throw new IllegalStateException(
                    "One source alias resolves to two certified binding types");
        }
    }

    private static Node projectNode(
            EGraphNode source,
            Map<String, Integer> aliases,
            Map<String, GraphType> aliasTypes,
            Map<String, String> localAliases,
            Map<String, GraphType> localTypes,
            int localDepth,
            Map<EGraphNode, BinderBlockDescriptor> localBinderDescriptors,
            Map<EGraphNode, Map<String, Integer>> localBinderSourceCoordinates,
            CertifiedContainers containers,
            CertifiedDependentChains chains) {
        if (source == null) {
            return null;
        }
        if (isLocalBinder(source)) {
            return projectLocalBinder(
                    source,
                    aliases,
                    aliasTypes,
                    localAliases,
                    localTypes,
                    localDepth,
                    localBinderDescriptors,
                    localBinderSourceCoordinates,
                    containers,
                    chains);
        }
        return projectOrdinaryNode(
                source,
                aliases,
                aliasTypes,
                localAliases,
                localTypes,
                localDepth,
                localBinderDescriptors,
                localBinderSourceCoordinates,
                containers,
                chains,
                false);
    }

    private static Node projectLocalBinder(
            EGraphNode source,
            Map<String, Integer> aliases,
            Map<String, GraphType> aliasTypes,
            Map<String, String> localAliases,
            Map<String, GraphType> localTypes,
            int localDepth,
            Map<EGraphNode, BinderBlockDescriptor> localBinderDescriptors,
            Map<EGraphNode, Map<String, Integer>> localBinderSourceCoordinates,
            CertifiedContainers containers,
            CertifiedDependentChains chains) {
        BinderBlockDescriptor descriptor = localBinderDescriptors.get(source);
        Map<String, Integer> sourceCoordinates = localBinderSourceCoordinates.get(source);
        if (descriptor == null || sourceCoordinates == null) {
            throw new IllegalStateException(
                    "A projected local binder lacks its certified coordinate plan");
        }
        if (!descriptor.hasCertifiedAutomorphisms()) {
            throw new IllegalStateException(
                    "A projected local binder has uncertified automorphisms");
        }

        List<Node> alternatives = new ArrayList<>();
        Map<String, GraphType> scopedTypes = new LinkedHashMap<>(localTypes);
        for (Map.Entry<String, Integer> entry : sourceCoordinates.entrySet()) {
            int coordinate = entry.getValue();
            if (coordinate < 0 || coordinate >= descriptor.coordinates().size()) {
                throw new IllegalStateException(
                        "A local source coordinate is outside its certified descriptor");
            }
            putAliasType(
                    scopedTypes,
                    entry.getKey(),
                    descriptor.coordinates().get(coordinate).canonicalSlot().type());
        }
        for (TypedPermutation permutation : descriptor.automorphisms().elements()) {
            Map<String, String> scopedAliases = new LinkedHashMap<>(localAliases);
            for (Map.Entry<String, Integer> entry : sourceCoordinates.entrySet()) {
                int target = permutedCoordinate(
                        descriptor, permutation, entry.getValue());
                scopedAliases.put(
                        entry.getKey(),
                        "@local[" + localDepth + ":" + target + "]");
            }
            Node alternative = projectOrdinaryNode(
                    source,
                    aliases,
                    aliasTypes,
                    scopedAliases,
                    scopedTypes,
                    localDepth + 1,
                    localBinderDescriptors,
                    localBinderSourceCoordinates,
                    containers,
                    chains,
                    true);
            boolean duplicate = false;
            for (Node retained : alternatives) {
                if (sameProjectedNode(retained, alternative)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                alternatives.add(alternative);
            }
        }
        if (alternatives.isEmpty()) {
            throw new IllegalStateException(
                    "A certified local binder has no automorphism alternatives");
        }
        Node primary = alternatives.get(0);
        if (alternatives.size() == 1) {
            return primary;
        }
        return new Node(
                primary.operator(),
                primary.payload(),
                primary.semanticPayload(),
                primary.lexicalVariable(),
                primary.bindingIndex(),
                primary.containerKind(),
                primary.orderInsensitive(),
                primary.children(),
                alternatives);
    }

    private static int permutedCoordinate(
            BinderBlockDescriptor descriptor,
            TypedPermutation permutation,
            int sourceCoordinate) {
        if (sourceCoordinate < 0 || sourceCoordinate >= descriptor.coordinates().size()) {
            throw new IllegalStateException(
                    "A local source coordinate is outside its certified descriptor");
        }
        TypedSlot target = permutation.apply(
                descriptor.coordinates().get(sourceCoordinate).canonicalSlot());
        for (int index = 0; index < descriptor.coordinates().size(); index++) {
            if (descriptor.coordinates().get(index).canonicalSlot().equals(target)) {
                return index;
            }
        }
        throw new IllegalStateException(
                "A local automorphism maps outside its certified descriptor");
    }

    private static Node projectOrdinaryNode(
            EGraphNode source,
            Map<String, Integer> aliases,
            Map<String, GraphType> aliasTypes,
            Map<String, String> localAliases,
            Map<String, GraphType> localTypes,
            int localDepth,
            Map<EGraphNode, BinderBlockDescriptor> localBinderDescriptors,
            Map<EGraphNode, Map<String, Integer>> localBinderSourceCoordinates,
            CertifiedContainers containers,
            CertifiedDependentChains chains,
            boolean mergeLocalDeclarations) {
        DependentChainCertificate dependentChain = chains.certificateFor(source);
        if (dependentChain != null) {
            return projectDependentChain(
                    source,
                    dependentChain,
                    aliases,
                    aliasTypes,
                    localAliases,
                    localTypes,
                    localDepth,
                    localBinderDescriptors,
                    localBinderSourceCoordinates,
                    containers,
                    chains);
        }
        ContainerKind containerKind = containerKind(source);
        if (!source.isFlexibleArity() && source.isOrderInsensitive()
                && !isCertifiedFixedCommutative(source.getOpcode())) {
            throw new IllegalStateException(
                    "No Alloy signature certificate for fixed commutativity of "
                            + source.getOpcode());
        }
        if (containerKind == ContainerKind.BAG
                || containerKind == ContainerKind.SET) {
            containers.require(source, containerKind, aliasTypes, localTypes);
        }
        List<Node> children = new ArrayList<>(source.getChildren().size());
        for (List<Integer> fiber : containers.childFibers(
                source, containerKind)) {
            List<Node> equivalentChildren = new ArrayList<>(fiber.size());
            for (int childIndex : fiber) {
                equivalentChildren.add(projectNode(
                        source.getChildren().get(childIndex),
                        aliases,
                        aliasTypes,
                        localAliases,
                        localTypes,
                        localDepth,
                        localBinderDescriptors,
                        localBinderSourceCoordinates,
                        containers,
                        chains));
            }
            Node projected = certifiedAlternativeNode(equivalentChildren);
            EGraphNode child = source.getChildren().get(fiber.get(0));
            if (mergeLocalDeclarations
                    && fiber.size() == 1
                    && isMergeableLocalDeclaration(child.getOpcode())
                    && !children.isEmpty()
                    && canMergeLocalDeclarations(
                            children.get(children.size() - 1), projected)) {
                children.set(
                        children.size() - 1,
                        mergeLocalDeclarations(children.get(children.size() - 1), projected));
            } else {
                children.add(projected);
            }
        }
        if (containerKind == ContainerKind.SET && children.size() > 1) {
            children = deduplicateSetChildren(children);
        }
        if (containerKind == ContainerKind.SET
                && containers.collapsedToSingleton(source)) {
            if (children.size() != 1) {
                throw new IllegalStateException(
                        "A certified singleton Set collapse retained another arity");
            }
            return children.get(0);
        }

        String operator = source.getOpcode().name();
        String payload = repairPayload(source);
        if (source.getOpcode() != Opcode.VARIABLE) {
            return new Node(
                    operator, payload, repairSemanticPayload(source), null, -1, containerKind,
                    source.isOrderInsensitive(), children);
        }

        String variable = firstNonempty(source.getAlphaName(), source.getSourceName());
        String localVariable = localAliases.get(variable);
        if (localVariable == null && source.getSourceName() != null) {
            localVariable = localAliases.get(source.getSourceName());
        }
        if (localVariable != null) {
            return new Node(operator, null, localVariable, -1, containerKind,
                    source.isOrderInsensitive(), children);
        }
        Integer bindingIndex = aliases.get(variable);
        if (bindingIndex == null && source.getSourceName() != null) {
            bindingIndex = aliases.get(source.getSourceName());
        }
        return bindingIndex == null
                ? new Node(operator, null, variable, -1, containerKind,
                        source.isOrderInsensitive(), children)
                : new Node(operator, null, variable, bindingIndex, containerKind,
                        source.isOrderInsensitive(), children);
    }

    private static Node projectDependentChain(
            EGraphNode source,
            DependentChainCertificate certificate,
            Map<String, Integer> aliases,
            Map<String, GraphType> aliasTypes,
            Map<String, String> localAliases,
            Map<String, GraphType> localTypes,
            int localDepth,
            Map<EGraphNode, BinderBlockDescriptor> localBinderDescriptors,
            Map<EGraphNode, Map<String, Integer>> localBinderSourceCoordinates,
            CertifiedContainers containers,
            CertifiedDependentChains chains) {
        Opcode opcode = certificate.source().kind() == DependentChainKind.JOIN
                ? Opcode.JOIN : Opcode.ARROW;
        if (source.getOpcode() != opcode) {
            throw new IllegalStateException(
                    "A dependent-chain certificate was attached to another source opcode");
        }
        List<EGraphNode> leaves = new ArrayList<>();
        collectDependentChainLeaves(
                source,
                opcode,
                leaves,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        if (leaves.size() != certificate.operandTypes().size()) {
            throw new IllegalStateException(
                    "A certified dependent chain changed its source occurrence count");
        }
        List<Node> children = new ArrayList<>(leaves.size());
        for (int index = 0; index < leaves.size(); index++) {
            EGraphNode leaf = leaves.get(index);
            GraphType checkedType = dependentLeafType(leaf, aliasTypes, localTypes);
            if (!checkedType.equals(certificate.operandTypes().get(index))) {
                throw new IllegalStateException(
                        "A certified dependent chain changed positional type proof " + index);
            }
            children.add(projectNode(
                    leaf,
                    aliases,
                    aliasTypes,
                    localAliases,
                    localTypes,
                    localDepth,
                    localBinderDescriptors,
                    localBinderSourceCoordinates,
                    containers,
                    chains));
        }
        return new Node(
                opcode.name(), null, repairSemanticPayload(source), null, -1,
                ContainerKind.SEQUENCE, false, children);
    }

    private static void collectDependentChainLeaves(
            EGraphNode source,
            Opcode opcode,
            List<EGraphNode> leaves,
            Set<EGraphNode> active) {
        if (source.getOpcode() != opcode) {
            leaves.add(source);
            return;
        }
        if (!active.add(source)) {
            throw new IllegalStateException(
                    "A certified dependent source chain is recursive");
        }
        try {
            source.requireAdmittedArity();
            if (source.getChildren().size() != 2) {
                throw new IllegalStateException(
                        "A dependent source chain is not binary syntax");
            }
            collectDependentChainLeaves(
                    source.getChildren().get(0), opcode, leaves, active);
            collectDependentChainLeaves(
                    source.getChildren().get(1), opcode, leaves, active);
        } finally {
            active.remove(source);
        }
    }

    private static GraphType dependentLeafType(
            EGraphNode source,
            Map<String, GraphType> aliasTypes,
            Map<String, GraphType> localTypes) {
        if (source.getExactAlloyType() != null) {
            GraphType stored = AlloyTypeBridge.graphType(
                    source.getExactAlloyType());
            return AlloyTypeBridge.isRelationFamily(stored)
                    ? stored
                    : DependentChainTheory.relationViewFromStoredType(stored);
        }
        if (source.getOpcode() != Opcode.VARIABLE) {
            throw new IllegalStateException(
                    "A dependent non-variable leaf lost its exact occurrence type");
        }
        String variable = firstNonempty(source.getAlphaName(), source.getSourceName());
        GraphType stored = localTypes.get(variable);
        if (stored == null && source.getSourceName() != null) {
            stored = localTypes.get(source.getSourceName());
        }
        if (stored == null) {
            stored = aliasTypes.get(variable);
        }
        if (stored == null && source.getSourceName() != null) {
            stored = aliasTypes.get(source.getSourceName());
        }
        if (stored == null) {
            throw new IllegalStateException(
                    "A dependent variable leaf has no certified stored type");
        }
        return DependentChainTheory.relationViewFromStoredType(stored);
    }

    private static boolean isLocalBinder(EGraphNode node) {
        switch (node.getOpcode()) {
            case SUM:
            case COMPREHENSION:
            case FORALL:
            case EXISTS:
            case NO:
            case ONE:
            case LONE:
                for (EGraphNode child : node.getChildren()) {
                    if (isRelDecl(child.getOpcode())) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }

    private static boolean isRelDecl(Opcode opcode) {
        return opcode == Opcode.GENERICRELDECL || opcode == Opcode.DISJ
                || opcode == Opcode.VAR || opcode == Opcode.DISJVAR;
    }

    private static boolean isMergeableLocalDeclaration(Opcode opcode) {
        return opcode == Opcode.GENERICRELDECL || opcode == Opcode.VAR;
    }

    private static boolean canMergeLocalDeclarations(Node left, Node right) {
        return left != null
                && right != null
                && left.operator().equals(right.operator())
                && ("GENERICRELDECL".equals(left.operator())
                        || "VAR".equals(left.operator()))
                && !left.children().isEmpty()
                && !right.children().isEmpty()
                && sameProjectedNode(left.children().get(0), right.children().get(0));
    }

    private static Node mergeLocalDeclarations(Node left, Node right) {
        List<Node> children = new ArrayList<>(left.children());
        children.addAll(right.children().subList(1, right.children().size()));
        return new Node(
                left.operator(),
                left.payload(),
                left.semanticPayload(),
                left.lexicalVariable(),
                left.bindingIndex(),
                left.containerKind(),
                left.orderInsensitive(),
                children);
    }

    private static boolean sameProjectedNode(Node left, Node right) {
        if (!left.certifiedAlternatives().isEmpty()
                || !right.certifiedAlternatives().isEmpty()) {
            List<Node> leftAlternatives = left.certifiedAlternatives().isEmpty()
                    ? List.of(left)
                    : left.certifiedAlternatives();
            List<Node> rightAlternatives = right.certifiedAlternatives().isEmpty()
                    ? List.of(right)
                    : right.certifiedAlternatives();
            for (Node leftAlternative : leftAlternatives) {
                for (Node rightAlternative : rightAlternatives) {
                    if (sameProjectedNode(leftAlternative, rightAlternative)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (!left.operator().equals(right.operator())
                || !Objects.equals(left.semanticPayload(), right.semanticPayload())
                || !Objects.equals(left.lexicalVariable(), right.lexicalVariable())
                || left.bindingIndex() != right.bindingIndex()
                || left.containerKind() != right.containerKind()
                || left.orderInsensitive() != right.orderInsensitive()
                || left.children().size() != right.children().size()) {
            return false;
        }
        for (int index = 0; index < left.children().size(); index++) {
            if (!sameProjectedNode(
                    left.children().get(index), right.children().get(index))) {
                return false;
            }
        }
        return true;
    }

    private static List<Node> deduplicateSetChildren(List<Node> children) {
        List<Node> result = new ArrayList<>(children.size());
        for (Node candidate : children) {
            boolean duplicate = false;
            for (Node retained : result) {
                if (sameProjectedNode(retained, candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static Node certifiedAlternativeNode(List<Node> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "A certified quotient fiber has no repair representative");
        }
        List<Node> unique = new ArrayList<>(candidates.size());
        for (Node candidate : candidates) {
            boolean duplicate = false;
            for (Node retained : unique) {
                if (sameProjectedNode(retained, candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(candidate);
            }
        }
        if (unique.size() == 1) {
            return unique.get(0);
        }
        Node primary = unique.get(0);
        return new Node(
                primary.operator(),
                primary.payload(),
                primary.semanticPayload(),
                primary.lexicalVariable(),
                primary.bindingIndex(),
                primary.containerKind(),
                primary.orderInsensitive(),
                primary.children(),
                unique);
    }

    private static ContainerKind containerKind(EGraphNode node) {
        SiblingQuotient quotient = node.getSiblingQuotient();
        if (quotient == SiblingQuotient.COMMUTATIVE_IDEMPOTENT_SET) {
            return ContainerKind.SET;
        }
        if (quotient == SiblingQuotient.COMMUTATIVE_BAG) {
            return ContainerKind.BAG;
        }
        if (!node.isFlexibleArity() && node.getChildren().size() == 1) {
            return ContainerKind.ONE;
        }
        return ContainerKind.SEQUENCE;
    }

    private static boolean isCertifiedFixedCommutative(Opcode opcode) {
        return opcode == Opcode.IFF
                || opcode == Opcode.EQUALS
                || opcode == Opcode.NOT_EQUALS
                || opcode == Opcode.IPLUS
                || opcode == Opcode.MUL;
    }

    private static String repairPayload(EGraphNode node) {
        switch (node.getOpcode()) {
            case GLOBALBINDING:
            case CONSTANT:
            case REF:
                return node.getSourceName();
            case CALL:
                return CallMetadata.semanticKey(node);
            default:
                return null;
        }
    }

    private static String repairSemanticPayload(EGraphNode node) {
        String identity;
        switch (node.getOpcode()) {
            case GLOBALBINDING:
                identity = node.getSemanticIdentity() == null
                        ? normalizeAtom(node.getSourceName())
                        : node.getSemanticIdentity();
                break;
            case CONSTANT:
            case REF:
            case SHADOW:
                identity = normalizeAtom(node.getSourceName());
                break;
            case CALL:
                identity = CallMetadata.semanticKey(node);
                break;
            default:
                identity = "";
                break;
        }
        GraphType type;
        if (isRelDecl(node.getOpcode())) {
            return "identity=" + identity + ";structure=certified-local-declaration";
        } else if (node.getMetatype() == EGraphNode.Metatype.BOOLEAN) {
            type = GraphType.BOOL;
        } else if (node.getOpcode() == Opcode.CARDINALITY
                || node.getOpcode() == Opcode.CAST2INT) {
            type = GraphType.INT;
        } else if (node.getExactAlloyType() != null) {
            type = AlloyTypeBridge.graphType(node.getExactAlloyType());
        } else {
            throw new IllegalStateException(
                    "A projected non-variable node lost its exact occurrence type: "
                            + node.getOpcode());
        }
        return "identity=" + identity + ";type=" + type;
    }

    private static Declaration sourceDeclaration(
            QuantiVar variable,
            String domain,
            int exchangeClass,
            List<Integer> dependencies) {
        return new Declaration(
                variable.getQuantifier().name(),
                normalizeType(variable.getTypeName()),
                variable.getCardinality().name(),
                variable.getDisjointnessClass(),
                domain,
                exchangeClass,
                dependencies);
    }

    private static Declaration certifiedDeclaration(
            QuantiVar variable,
            BinderBlockDescriptor descriptor,
            int coordinateIndex) {
        BinderCoordinateDescriptor coordinate = descriptor.coordinates().get(coordinateIndex);
        Map<TypedSlot, Integer> indices = coordinateIndices(descriptor);
        List<Integer> dependencies = new ArrayList<>(coordinate.dependencies().size());
        for (TypedSlot dependency : coordinate.dependencies()) {
            Integer index = indices.get(dependency);
            if (index == null) {
                throw new IllegalStateException(
                        "A certified binder dependency escaped its descriptor");
            }
            dependencies.add(index);
        }
        return sourceDeclaration(
                variable,
                coordinate.domain().stableString(),
                coordinate.exchangeClass(),
                dependencies);
    }

    private static Map<TypedSlot, Integer> coordinateIndices(
            BinderBlockDescriptor descriptor) {
        Map<TypedSlot, Integer> result = new HashMap<>();
        for (int index = 0; index < descriptor.coordinates().size(); index++) {
            result.put(descriptor.coordinates().get(index).canonicalSlot(), index);
        }
        return result;
    }

    private static List<Integer> certifiedOrbit(
            BinderBlockDescriptor descriptor,
            int coordinateIndex) {
        TypedSlot coordinate = descriptor.coordinates().get(coordinateIndex).canonicalSlot();
        Map<TypedSlot, Integer> indices = coordinateIndices(descriptor);
        Set<Integer> orbit = new LinkedHashSet<>();
        for (TypedPermutation action : descriptor.automorphisms().elements()) {
            Integer image = indices.get(action.apply(coordinate));
            if (image == null) {
                throw new IllegalStateException(
                        "A certified binder automorphism escaped its descriptor");
            }
            orbit.add(image);
        }
        List<Integer> result = new ArrayList<>(orbit);
        Collections.sort(result);
        return result;
    }

    private static void requireDescriptorMatches(
            List<QuantiVar> variables,
            BinderBlockDescriptor descriptor,
            List<Integer> sourceCoordinates,
            int phaseIndex) {
        if (variables.size() != descriptor.coordinates().size()
                || variables.size() != sourceCoordinates.size()) {
            throw new IllegalStateException(
                    "Certified binder arity differs from repaired phase " + phaseIndex);
        }
        boolean[] seenCoordinates = new boolean[variables.size()];
        Map<Integer, Integer> disjointnessClasses = new LinkedHashMap<>();
        for (int index = 0; index < variables.size(); index++) {
            QuantiVar variable = variables.get(index);
            int coordinateIndex = sourceCoordinates.get(index);
            if (coordinateIndex < 0 || coordinateIndex >= variables.size()
                    || seenCoordinates[coordinateIndex]) {
                throw new IllegalStateException(
                        "Certified source-coordinate map is not a permutation in phase "
                                + phaseIndex);
            }
            seenCoordinates[coordinateIndex] = true;
            BinderCoordinateDescriptor coordinate = descriptor.coordinates().get(
                    coordinateIndex);
            int sourceDisjointness = variable.getDisjointnessClass();
            int certifiedDisjointness = coordinate.disjointnessClass();
            boolean matchingDisjointness;
            if (sourceDisjointness <= 0) {
                matchingDisjointness = certifiedDisjointness
                        == BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS;
            } else {
                Integer prior = disjointnessClasses.putIfAbsent(
                        sourceDisjointness, certifiedDisjointness);
                matchingDisjointness = certifiedDisjointness >= 0
                        && (prior == null || prior == certifiedDisjointness);
            }
            if (!variable.getQuantifier().name().equals(coordinate.quantifier())
                    || !variable.getCardinality().name().equals(coordinate.multiplicity())
                    || !matchingDisjointness) {
                throw new IllegalStateException(
                        "Certified binder payload differs from repaired phase "
                                + phaseIndex + " coordinate " + index);
            }
        }
        requireLegacyAlignmentSpaceCertified(descriptor, phaseIndex);
    }

    /**
     * The established metric permits every permutation inside one compatible
     * declaration class. Prove that this entire space is present in Aut(beta)
     * before exposing it to the pairwise alpha search.
     */
    private static void requireLegacyAlignmentSpaceCertified(
            BinderBlockDescriptor descriptor,
            int phaseIndex) {
        if (!descriptor.hasCertifiedAutomorphisms()) {
            throw new IllegalStateException(
                    "Repair phase " + phaseIndex
                            + " has uncertified binder automorphisms");
        }
        List<BinderCoordinateDescriptor> coordinates = descriptor.coordinates();
        List<List<Integer>> classes = new ArrayList<>();
        for (int index = 0; index < coordinates.size(); index++) {
            boolean placed = false;
            for (List<Integer> group : classes) {
                if (sameAlignmentPayload(
                        coordinates.get(group.get(0)), coordinates.get(index))) {
                    group.add(index);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<Integer> group = new ArrayList<>();
                group.add(index);
                classes.add(group);
            }
        }
        for (List<Integer> group : classes) {
            for (int index = 1; index < group.size(); index++) {
                TypedPermutation adjacentSwap = coordinateSwap(
                        descriptor, group.get(index - 1), group.get(index));
                if (!descriptor.automorphisms().contains(adjacentSwap)) {
                    throw new IllegalStateException(
                            "Certified binder group for repair phase " + phaseIndex
                                    + " is narrower than the established metric's "
                                    + "declaration-permutation space");
                }
            }
        }
    }

    private static boolean sameAlignmentPayload(
            BinderCoordinateDescriptor left,
            BinderCoordinateDescriptor right) {
        return left.type().equals(right.type())
                && left.domain().equals(right.domain())
                && left.quantifier().equals(right.quantifier())
                && left.multiplicity().equals(right.multiplicity())
                && left.disjointnessClass() == right.disjointnessClass()
                && left.exchangeClass() == right.exchangeClass()
                && left.dependencies().equals(right.dependencies());
    }

    private static TypedPermutation coordinateSwap(
            BinderBlockDescriptor descriptor,
            int leftIndex,
            int rightIndex) {
        Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
        for (TypedSlot slot : descriptor.boundContext()) {
            mapping.put(slot, slot);
        }
        TypedSlot left = descriptor.coordinates().get(leftIndex).canonicalSlot();
        TypedSlot right = descriptor.coordinates().get(rightIndex).canonicalSlot();
        mapping.put(left, right);
        mapping.put(right, left);
        return TypedPermutation.of(descriptor.boundContext(), mapping);
    }

    /**
     * Gives temporal phases one deterministic occurrence order induced by the
     * already-certified matrix container laws. ACI parents compare expanded
     * temporal operands by semantic content; ordered parents and binary
     * temporal branch roles retain source order. The same reindexing is then
     * applied to matrices and binder owners, so all metric components use one
     * coherent phase presentation.
     */
    private static final class CanonicalPhaseProjection {
        private final List<NormalForm> forms;
        private final List<Phase> sourcePhases;
        private final IdentityHashMap<NormalForm, Integer> sourceIndices =
                new IdentityHashMap<>();
        private final Map<Integer, StructuralKey> phaseKeys = new HashMap<>();
        private final Set<Integer> activeKeys = new LinkedHashSet<>();
        private final List<Integer> sourceOrder = new ArrayList<>();
        private final Set<Integer> visited = new LinkedHashSet<>();
        private final Map<Integer, Integer> canonicalIndices = new HashMap<>();
        private final List<Phase> phases;
        private final TemporalNode temporalRoot;

        private CanonicalPhaseProjection(
                List<NormalForm> forms,
                List<Phase> sourcePhases) {
            this.forms = List.copyOf(forms);
            this.sourcePhases = List.copyOf(sourcePhases);
            if (this.forms.size() != this.sourcePhases.size()) {
                throw new IllegalArgumentException(
                        "Temporal forms and projected phases have different cardinalities");
            }
            for (int index = 0; index < this.forms.size(); index++) {
                if (sourceIndices.put(this.forms.get(index), index) != null) {
                    throw new IllegalStateException(
                            "One temporal NormalForm occurs twice in the phase list");
                }
            }
            if (this.forms.isEmpty()) {
                phases = Collections.emptyList();
                temporalRoot = new TemporalNode(
                        naturalTemporalLabel(TemporalOp.NONE),
                        Collections.emptyList());
                return;
            }

            visitPhase(0);
            // The exact adapter has already proved every source phase reachable.
            // A phase can disappear here only when a certified alternative (most
            // notably an idempotent Set fiber) selects an equivalent occurrence.
            // Retaining that redundant phase would charge for syntax removed by
            // the certified quotient and would leave no matrix reference to it.
            for (int index = 0; index < sourceOrder.size(); index++) {
                canonicalIndices.put(sourceOrder.get(index), index);
            }
            List<Phase> reordered = new ArrayList<>(sourceOrder.size());
            for (int source : sourceOrder) {
                reordered.add(reindexPhase(sourcePhases.get(source), source));
            }
            phases = Collections.unmodifiableList(reordered);
            temporalRoot = new TemporalNode(
                    naturalTemporalLabel(TemporalOp.NONE), temporalChildren(0));
        }

        private static CanonicalPhaseProjection create(
                List<NormalForm> forms,
                List<Phase> phases) {
            return new CanonicalPhaseProjection(forms, phases);
        }

        private void visitPhase(int sourceIndex) {
            if (!visited.add(sourceIndex)) {
                return;
            }
            sourceOrder.add(sourceIndex);
            for (TemporalReference reference : orderedReferences(sourceIndex)) {
                for (int branch : reference.branches) {
                    visitPhase(branch);
                }
            }
        }

        private Phase reindexPhase(Phase phase, int sourceIndex) {
            List<Binding> bindings = new ArrayList<>(phase.bindings().size());
            for (Binding binding : phase.bindings()) {
                int owner = binding.ownerPhase();
                if (owner >= 0) {
                    Integer mapped = canonicalIndices.get(owner);
                    if (mapped == null) {
                        throw new IllegalStateException(
                                "A binding owner is absent from canonical phase order");
                    }
                    owner = mapped;
                }
                bindings.add(new Binding(
                        binding.role(),
                        binding.ordinal(),
                        owner,
                        binding.coordinate(),
                        binding.declaration(),
                        binding.bindingPath(),
                        binding.ownerContext(),
                        binding.certifiedOrbit(),
                        binding.prenexPathErasureCertified()));
            }
            return new Phase(
                    phase.quantifiers(),
                    bindings,
                    rewriteReferences(phase.matrix(), sourceIndex));
        }

        private Node rewriteReferences(Node node, int parentPhase) {
            if (node == null) {
                return null;
            }
            TemporalReference reference = temporalReference(node, parentPhase);
            if (reference != null) {
                return new Node(
                        node.operator(),
                        node.payload(),
                        reference.canonicalPayload(canonicalIndices),
                        node.lexicalVariable(),
                        node.bindingIndex(),
                        node.containerKind(),
                        node.orderInsensitive(),
                        Collections.emptyList(),
                        Collections.emptyList());
            }
            List<Node> children = new ArrayList<>(node.children().size());
            for (Node child : node.children()) {
                children.add(rewriteReferences(child, parentPhase));
            }
            List<Node> alternatives = new ArrayList<>(
                    node.certifiedAlternatives().size());
            for (Node alternative : node.certifiedAlternatives()) {
                alternatives.add(rewriteReferences(alternative, parentPhase));
            }
            return new Node(
                    node.operator(),
                    node.payload(),
                    node.semanticPayload(),
                    node.lexicalVariable(),
                    node.bindingIndex(),
                    node.containerKind(),
                    node.orderInsensitive(),
                    children,
                    alternatives);
        }

        private List<TemporalNode> temporalChildren(int phaseIndex) {
            List<TemporalNode> result = new ArrayList<>();
            for (TemporalReference reference : orderedReferences(phaseIndex)) {
                List<TemporalNode> descendants = new ArrayList<>();
                for (int branch : reference.branches) {
                    descendants.addAll(temporalChildren(branch));
                }
                result.add(new TemporalNode(reference.label, descendants));
            }
            return result;
        }

        private List<TemporalReference> orderedReferences(int phaseIndex) {
            List<TemporalReference> result = new ArrayList<>();
            collectReferences(sourcePhases.get(phaseIndex).matrix(), phaseIndex, result);
            return result;
        }

        private void collectReferences(
                Node node,
                int parentPhase,
                List<TemporalReference> result) {
            if (node == null) {
                return;
            }
            TemporalReference reference = temporalReference(node, parentPhase);
            if (reference != null) {
                result.add(reference);
                return;
            }
            Node selected = selectedAlternative(node, parentPhase);
            List<Node> children = new ArrayList<>(selected.children());
            if (selected.orderInsensitive()) {
                children.sort((left, right) -> matrixKey(left, parentPhase)
                        .compareTo(matrixKey(right, parentPhase)));
            }
            for (Node child : children) {
                collectReferences(child, parentPhase, result);
            }
        }

        private Node selectedAlternative(Node node, int parentPhase) {
            if (node.certifiedAlternatives().isEmpty()) {
                return node;
            }
            Node selected = null;
            StructuralKey selectedKey = null;
            for (Node alternative : node.certifiedAlternatives()) {
                StructuralKey key = matrixKey(alternative, parentPhase);
                if (selected == null || key.compareTo(selectedKey) < 0) {
                    selected = alternative;
                    selectedKey = key;
                }
            }
            return selected;
        }

        private StructuralKey phaseKey(int phaseIndex) {
            StructuralKey remembered = phaseKeys.get(phaseIndex);
            if (remembered != null) {
                return remembered;
            }
            if (!activeKeys.add(phaseIndex)) {
                throw new IllegalStateException(
                        "Temporal phase references contain a cycle");
            }
            try {
                List<StructuralKey> children = new ArrayList<>();
                for (Declaration declaration
                        : canonicalDeclarations(sourcePhases.get(phaseIndex).quantifiers())) {
                    children.add(declarationKey(declaration));
                }
                children.add(matrixKey(sourcePhases.get(phaseIndex).matrix(), phaseIndex));
                StructuralKey result = StructuralKey.branch(
                        "repair-phase-body-v1", children);
                phaseKeys.put(phaseIndex, result);
                return result;
            } finally {
                activeKeys.remove(phaseIndex);
            }
        }

        private StructuralKey matrixKey(Node node, int parentPhase) {
            if (node == null) {
                return StructuralKey.leaf("repair-null-matrix-v1");
            }
            TemporalReference reference = temporalReference(node, parentPhase);
            if (reference != null) {
                List<StructuralKey> branches = new ArrayList<>(reference.branches.length);
                for (int branch : reference.branches) {
                    branches.add(phaseKey(branch));
                }
                return StructuralKey.of(
                        "repair-temporal-occurrence-v1",
                        List.of(reference.label),
                        branches);
            }
            if (!node.certifiedAlternatives().isEmpty()) {
                StructuralKey minimum = null;
                for (Node alternative : node.certifiedAlternatives()) {
                    StructuralKey candidate = matrixKey(alternative, parentPhase);
                    if (minimum == null || candidate.compareTo(minimum) < 0) {
                        minimum = candidate;
                    }
                }
                return minimum;
            }
            List<StructuralKey> children = new ArrayList<>(node.children().size());
            for (Node child : node.children()) {
                children.add(matrixKey(child, parentPhase));
            }
            if (node.orderInsensitive()) {
                children.sort(StructuralKey::compareTo);
                if (node.containerKind() == ContainerKind.SET) {
                    List<StructuralKey> unique = new ArrayList<>(children.size());
                    StructuralKey prior = null;
                    for (StructuralKey child : children) {
                        if (!child.equals(prior)) {
                            unique.add(child);
                            prior = child;
                        }
                    }
                    children = unique;
                }
            }
            return StructuralKey.of(
                    "repair-matrix-node-v1",
                    List.of(
                            node.operator(),
                            nullToEmpty(node.semanticPayload()),
                            node.containerKind().name(),
                            Boolean.toString(node.orderInsensitive()),
                            node.isVariable()
                                    ? Integer.toString(node.bindingIndex())
                                    : ""),
                    children);
        }

        private TemporalReference temporalReference(Node node, int parentPhase) {
            if (!"REF".equals(node.operator())) {
                return null;
            }
            String source = node.payload();
            if (source == null || !source.startsWith("temporal[")
                    || !source.endsWith("]")) {
                return null;
            }
            int colon = source.indexOf(':', "temporal[".length());
            if (colon < 0) {
                throw new IllegalStateException(
                        "Malformed temporal reference " + source);
            }
            int localIndex;
            int arity;
            try {
                localIndex = Integer.parseInt(source.substring(
                        "temporal[".length(), colon));
                arity = Integer.parseInt(source.substring(colon + 1, source.length() - 1));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException(
                        "Malformed temporal reference " + source, exception);
            }
            List<NormalForm> children = forms.get(parentPhase).getTemporalChildren();
            if (localIndex < 0 || (arity != 1 && arity != 2)
                    || localIndex + arity > children.size()) {
                throw new IllegalStateException(
                        "Temporal reference is outside its parent phase: " + source);
            }
            int[] branches = new int[arity];
            for (int offset = 0; offset < arity; offset++) {
                Integer sourceIndex = sourceIndices.get(children.get(localIndex + offset));
                if (sourceIndex == null) {
                    throw new IllegalStateException(
                            "Temporal reference names an undeclared child phase");
                }
                branches[offset] = sourceIndex;
            }
            TemporalOp first = children.get(localIndex).getTemporalOp();
            if (arity == 2 && (!isBinaryLeft(first)
                    || matchingRight(first)
                            != children.get(localIndex + 1).getTemporalOp())) {
                throw new IllegalStateException(
                        "Temporal binary reference has mismatched branch roles: " + source);
            }
            return new TemporalReference(
                    naturalTemporalLabel(first), branches);
        }

        private static List<Declaration> canonicalDeclarations(
                List<Declaration> source) {
            List<Declaration> result = new ArrayList<>(source);
            for (int start = 0; start < result.size();) {
                String quantifier = result.get(start).quantifier();
                int end = start + 1;
                if ("ALL".equals(quantifier) || "SOME".equals(quantifier)) {
                    while (end < result.size()
                            && quantifier.equals(result.get(end).quantifier())) {
                        end++;
                    }
                    result.subList(start, end).sort((left, right) ->
                            declarationKey(left).compareTo(declarationKey(right)));
                }
                start = end;
            }
            return result;
        }

        private static StructuralKey declarationKey(Declaration declaration) {
            return StructuralKey.of(
                    "repair-declaration-v1",
                    List.of(
                            declaration.quantifier(),
                            declaration.type(),
                            declaration.cardinality(),
                            Integer.toString(declaration.disjointnessClass()),
                            declaration.certifiedDomain(),
                            Integer.toString(declaration.exchangeClass())),
                    declaration.dependencies().stream()
                            .map(value -> StructuralKey.leaf(
                                    "repair-dependency-v1", Integer.toString(value)))
                            .toList());
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    private static final class TemporalReference {
        private final String label;
        private final int[] branches;

        private TemporalReference(String label, int[] branches) {
            this.label = Objects.requireNonNull(label, "temporal label");
            this.branches = branches.clone();
        }

        private String canonicalPayload(Map<Integer, Integer> canonicalIndices) {
            StringBuilder result = new StringBuilder("temporal-phase[");
            for (int index = 0; index < branches.length; index++) {
                if (index != 0) {
                    result.append(',');
                }
                Integer canonical = canonicalIndices.get(branches[index]);
                if (canonical == null) {
                    throw new IllegalStateException(
                            "Temporal branch is absent from canonical phase order");
                }
                result.append(canonical);
            }
            return result.append("]/" ).append(label).toString();
        }
    }

    private static boolean isBinaryLeft(TemporalOp operation) {
        return operation == TemporalOp.UNTILL
                || operation == TemporalOp.RELEASESL
                || operation == TemporalOp.SINCEL
                || operation == TemporalOp.TRIGGEREDL;
    }

    private static TemporalOp matchingRight(TemporalOp operation) {
        switch (operation) {
            case UNTILL:
                return TemporalOp.UNTILR;
            case RELEASESL:
                return TemporalOp.RELEASESR;
            case SINCEL:
                return TemporalOp.SINCER;
            case TRIGGEREDL:
                return TemporalOp.TRIGGEREDR;
            default:
                return operation;
        }
    }

    private static String naturalTemporalLabel(TemporalOp operation) {
        switch (operation) {
            case UNTILL:
            case UNTILR:
                return "UNTIL";
            case RELEASESL:
            case RELEASESR:
                return "RELEASES";
            case SINCEL:
            case SINCER:
                return "SINCE";
            case TRIGGEREDL:
            case TRIGGEREDR:
                return "TRIGGERED";
            default:
                return operation.name();
        }
    }

    private static String sourceSymbol(EGraphNode node) {
        StringBuilder result = new StringBuilder("ALLOY/").append(node.getOpcode());
        switch (node.getOpcode()) {
            case CONSTANT:
            case GLOBALBINDING:
                result.append('/').append(normalizeAtom(node.getSourceName()));
                break;
            case CALL:
                result.append('/').append(CallMetadata.semanticKey(node));
                break;
            case SHADOW:
                result.append('/').append(normalizeAtom(node.getSourceName()));
                break;
            default:
                break;
        }
        return result.toString();
    }

    private static String normalizeAtom(String value) {
        return value == null
                ? ""
                : value.replace("this/", "").replaceAll("\\s+", "").trim();
    }

    private static String normalizeType(String type) {
        String normalized = normalizeAtom(type);
        if (normalized.startsWith("VAR_")) {
            return normalized.substring(4);
        }
        return normalized;
    }

    private static String requireTypeName(String type, String label) {
        requireAdmittedTypeName(type, label);
        String normalized = normalizeType(type);
        if (normalized.isEmpty()) {
            throw new IllegalStateException(label + " has no source type provenance");
        }
        return normalized;
    }

    private static void requireAdmittedTypeName(String type, String label) {
        if (!AlloyTypeBridge.isAdmittedIdentity(type)) {
            throw new IllegalStateException(
                    label + " must have a well-formed visible type identity");
        }
    }

    private static String firstNonempty(String first, String second) {
        return first != null && !first.isEmpty() ? first : second;
    }

    private static final class Origin {
        private final int phase;
        private final int coordinate;

        private Origin(int phase, int coordinate) {
            this.phase = phase;
            this.coordinate = coordinate;
        }
    }

    private static final class OriginIndex {
        private final IdentityHashMap<QuantiVar, Origin> identities = new IdentityHashMap<>();
        private final Map<Integer, Origin> ids = new HashMap<>();
        private final Map<String, Origin> deBruijn = new HashMap<>();

        private OriginIndex(
                List<NormalForm> normalForms,
                List<BinderBlockDescriptor> descriptors,
                List<? extends List<Integer>> sourceCoordinates) {
            for (int phase = 0; phase < normalForms.size(); phase++) {
                List<QuantiVar> variables = normalForms.get(phase).getMatrixQuantiVars();
                List<Integer> coordinateMap = sourceCoordinates.get(phase);
                requireDescriptorMatches(
                        variables, descriptors.get(phase), coordinateMap, phase);
                for (int source = 0; source < variables.size(); source++) {
                    QuantiVar variable = variables.get(source);
                    Origin origin = new Origin(phase, coordinateMap.get(source));
                    identities.put(variable, origin);
                    ids.putIfAbsent(variable.getId(), origin);
                    deBruijn.putIfAbsent(variable.getDeBruijnKey(), origin);
                }
            }
        }

        private Origin find(QuantiVar variable) {
            Origin result = identities.get(variable);
            if (result == null) {
                result = ids.get(variable.getId());
            }
            if (result == null) {
                result = deBruijn.get(variable.getDeBruijnKey());
            }
            if (result == null) {
                throw new IllegalStateException(
                        "An inherited repaired binding has no certified owner phase");
            }
            return result;
        }
    }

    /** Identity-bound bridge from repaired syntax to checked chain certificates. */
    private static final class CertifiedDependentChains {
        private final Map<EGraphNode, DependentChainCertificate> certificates;

        private CertifiedDependentChains(
                Map<EGraphNode, DependentChainCertificate> certificates) {
            this.certificates = certificates;
        }

        private static CertifiedDependentChains from(
                CertifiedSemanticArtifact artifact,
                Map<EGraphNode, DependentChainSourceBinding> sourceBindings) {
            Set<DependentChainCertificate> admitted = Collections.newSetFromMap(
                    new IdentityHashMap<>());
            admitted.addAll(artifact.dependentChainConstructions());
            IdentityHashMap<EGraphNode, DependentChainCertificate> checked =
                    new IdentityHashMap<>();
            Set<DependentChainCertificate> used = Collections.newSetFromMap(
                    new IdentityHashMap<>());
            for (Map.Entry<EGraphNode, DependentChainSourceBinding> entry
                    : sourceBindings.entrySet()) {
                EGraphNode source = Objects.requireNonNull(
                        entry.getKey(), "dependent source occurrence");
                DependentChainSourceBinding binding = Objects.requireNonNull(
                        entry.getValue(), "dependent source binding");
                binding.requireMatches(source);
                DependentChainCertificate certificate = binding.certificate();
                if (!admitted.contains(certificate)) {
                    throw new IllegalArgumentException(
                            "Repair projection received a chain certificate outside its artifact");
                }
                if (!used.add(certificate)) {
                    throw new IllegalArgumentException(
                            "One chain certificate was replayed over multiple source roots");
                }
                Opcode expected = certificate.source().kind() == DependentChainKind.JOIN
                        ? Opcode.JOIN : Opcode.ARROW;
                if (source.getOpcode() != expected
                        || source.getChildren().size() != 2
                        || source.getExactAlloyType() == null
                        || !matchesCertifiedResultType(
                                source, certificate.target().outputType())) {
                    throw new IllegalArgumentException(
                            "Repair projection received a chain certificate for another source");
                }
                checked.put(source, certificate);
            }
            return new CertifiedDependentChains(Collections.unmodifiableMap(checked));
        }

        private static boolean matchesCertifiedResultType(
                EGraphNode source,
                GraphType certifiedRelationType) {
            GraphType storedType = AlloyTypeBridge.graphType(
                    source.getExactAlloyType());
            try {
                DependentChainTheory.LeafTypeRule rule =
                        DependentChainTheory.requireLeafTypeProof(
                                storedType, certifiedRelationType);
                DependentChainTheory.leafTypeProof(
                        rule, storedType, certifiedRelationType);
                return true;
            } catch (IllegalArgumentException unsupported) {
                return false;
            }
        }

        private DependentChainCertificate certificateFor(EGraphNode source) {
            return certificates.get(source);
        }
    }

    private static final class CertifiedContainers {
        private final SemanticProfile semanticProfile;
        private final Map<String, List<ContainerLawDeclaration>> declarations;
        private final Map<EGraphNode, CertifiedSetOperandPartition> setPartitions;

        private CertifiedContainers(
                SemanticProfile semanticProfile,
                Map<String, List<ContainerLawDeclaration>> declarations,
                Map<EGraphNode, CertifiedSetOperandPartition> setPartitions) {
            this.semanticProfile = semanticProfile;
            this.declarations = declarations;
            this.setPartitions = setPartitions;
        }

        private static CertifiedContainers from(
                CertifiedSemanticArtifact artifact,
                Map<EGraphNode, CertifiedSetOperandPartition> setPartitions) {
            return new CertifiedContainers(
                    artifact.semanticProfile(),
                    artifact.containerLaws(),
                    Objects.requireNonNull(setPartitions, "setPartitions"));
        }

        private List<List<Integer>> childFibers(
                EGraphNode source,
                ContainerKind kind) {
            CertifiedSetOperandPartition partition = kind == ContainerKind.SET
                    ? setPartitions.get(source) : null;
            if (partition != null) {
                return partition.inputFibers(source);
            }
            List<List<Integer>> all = new ArrayList<>(source.getChildren().size());
            for (int index = 0; index < source.getChildren().size(); index++) {
                all.add(Collections.singletonList(index));
            }
            return all;
        }

        private boolean collapsedToSingleton(EGraphNode source) {
            CertifiedSetOperandPartition partition = setPartitions.get(source);
            return partition != null && partition.inputFibers(source).size() == 1;
        }

        private static ContainerKind portKind(ContainerLawDeclaration.Kind kind) {
            switch (kind) {
                case SEQ:
                    return ContainerKind.SEQUENCE;
                case BAG:
                    return ContainerKind.BAG;
                case SET:
                    return ContainerKind.SET;
                default:
                    throw new IllegalStateException(
                            "A certified container registry contains " + kind);
            }
        }

        private void require(
                EGraphNode source,
                ContainerKind expected,
                Map<String, GraphType> aliasTypes,
                Map<String, GraphType> localTypes) {
            String symbol = sourceSymbol(source);
            if (!semanticProfile.equals(source.getSemanticProfile())) {
                throw new IllegalStateException(
                        "The repair projection crossed semantic profiles for " + symbol);
            }
            GraphType resultType = resultType(source);
            PortSchema schema = schema(
                    source,
                    expected,
                    containerElementType(source, aliasTypes, localTypes));
            List<ContainerLawDeclaration> candidates = declarations.get(symbol);
            if (candidates != null) {
                for (ContainerLawDeclaration declaration : candidates) {
                    if (matches(
                            declaration,
                            source,
                            expected,
                            symbol,
                            resultType,
                            schema)) {
                        return;
                    }
                }
            }
            throw new IllegalStateException(
                    "The repair projection requested an uncertified exact "
                            + expected + " instance for " + symbol + " under "
                            + semanticProfile.fingerprint()
                            + "; requested result=" + resultType
                            + ", schema=" + schema
                            + ", certified=" + certifiedSchemas(candidates));
        }

        private static List<String> certifiedSchemas(
                List<ContainerLawDeclaration> candidates) {
            if (candidates == null) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (ContainerLawDeclaration declaration : candidates) {
                for (ContainerLawCertificate certificate
                        : declaration.certificates().values()) {
                    result.add(certificate.schema().toString());
                }
            }
            return result;
        }

        private boolean matches(
                ContainerLawDeclaration declaration,
                EGraphNode source,
                ContainerKind expected,
                String symbol,
                GraphType resultType,
                PortSchema schema) {
            if (portKind(declaration.kind()) != expected
                    || declaration.associative() != source.hasFlatLicense()
                    || declaration.hasUnit()
                            != (source.getUnitLicense()
                                    == is.fivefivefive.CanDis.theory.UnitLicense.EXPLICIT)) {
                return false;
            }
            for (ContainerLawCertificate certificate
                    : declaration.certificates().values()) {
                if (certificate.authority()
                                != ContainerLawCertificate.Authority.ALLOY_PROFILE_THEORY
                        || !certificate.appliesTo(
                                semanticProfile,
                                symbol,
                                resultType,
                                PortPath.at(0),
                                schema)) {
                    return false;
                }
            }
            return true;
        }

        private static PortSchema schema(
                EGraphNode source,
                ContainerKind kind,
                GraphType elementType) {
            OnePortSchema element = new OnePortSchema(elementType);
            switch (kind) {
                case BAG:
                    return new BagPortSchema(source.getArityPolicy(), element);
                case SET:
                    return new SetPortSchema(source.getArityPolicy(), element);
                case SEQUENCE:
                    return new SeqPortSchema(source.getArityPolicy(), element);
                default:
                    throw new IllegalStateException(
                            "No certified sibling quotient is required for " + kind);
            }
        }

        private static GraphType resultType(EGraphNode source) {
            return requireExactResultType(source);
        }

        private static GraphType containerElementType(
                EGraphNode source,
                Map<String, GraphType> aliasTypes,
                Map<String, GraphType> localTypes) {
            switch (source.getOpcode()) {
                case AND:
                case OR:
                case IFF:
                    return GraphType.BOOL;
                case IPLUS:
                case MUL:
                    return GraphType.INT;
                case PLUS:
                case INTERSECT:
                    return resultType(source);
                case EQUALS:
                case NOT_EQUALS:
                case DISJOINT:
                    List<GraphType> operandTypes = new ArrayList<>();
                    for (EGraphNode child : source.getChildren()) {
                        operandTypes.add(operandType(child, aliasTypes, localTypes));
                    }
                    return AlloyTypeBridge.commutativeCarrier(operandTypes);
                default:
                    return resultType(source);
            }
        }

        private static GraphType operandType(
                EGraphNode source,
                Map<String, GraphType> aliasTypes,
                Map<String, GraphType> localTypes) {
            if (source.getOpcode() != Opcode.VARIABLE) {
                return resultType(source);
            }
            for (String alias : new String[] {
                    source.getAlphaName(), source.getSourceName() }) {
                if (alias == null || alias.isEmpty()) {
                    continue;
                }
                GraphType local = localTypes.get(alias);
                if (local != null) {
                    return local;
                }
                GraphType bound = aliasTypes.get(alias);
                if (bound != null) {
                    return bound;
                }
            }
            throw new IllegalStateException(
                    "A projected variable has no certified binding type: "
                            + firstNonempty(source.getAlphaName(), source.getSourceName()));
        }
    }

    private static GraphType bindingType(QuantiVar variable) {
        is.fivefivefive.ACGN.alloy.ExactAlloyType exact =
                variable.getExactAlloyType();
        if (exact != null
                && exact.kind()
                        == is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.RELATION
                && exact.relationArity() > 1) {
            return AlloyTypeBridge.graphType(exact);
        }
        String type = requireTypeName(variable.getTypeName(), "repair binding");
        if ("int".equalsIgnoreCase(type)) {
            return GraphType.INT;
        }
        return GraphType.constructor(
                "AlloyCarrier",
                Collections.singletonList(GraphType.constructor(type)));
    }
}
