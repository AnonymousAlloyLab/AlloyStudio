package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Immutable read-only boundary for one quiescent, certificate-checked semantic
 * graph and its complete bounded observations.
 */
public final class CertifiedSemanticArtifact {
    private final TypedInvocation root;
    private final Map<EClassId, TypedEClassRecord> classes;
    private final CoherentWitnessFamily witnesses;
    private final List<FiniteUnfoldingTree> unfoldings;
    private final Map<String, List<ContainerLawDeclaration>> containerLaws;
    private final List<FlatConstructionCertificate> flatConstructions;
    private final List<ContainerConstructionCertificate> containerConstructions;
    private final List<DependentChainCertificate> dependentChainConstructions;
    private final List<CallOccurrenceCertificate> callOccurrenceCertificates;
    private final ConstructionSourceLedger constructionSources;
    private final List<BinderOccurrenceAutomorphismCertificate> binderOccurrenceCertificates;
    private final SemanticProfile semanticProfile;

    CertifiedSemanticArtifact(
            TypedInvocation root,
            Map<EClassId, TypedEClassRecord> classes,
            CoherentWitnessFamily witnesses,
            List<? extends FiniteUnfoldingTree> unfoldings,
            Map<String, ? extends List<? extends ContainerLawDeclaration>> containerLaws,
            SemanticProfile semanticProfile) {
        this(
                root,
                classes,
                witnesses,
                unfoldings,
                containerLaws,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                ConstructionSourceLedger.empty(semanticProfile),
                semanticProfile);
    }

    CertifiedSemanticArtifact(
            TypedInvocation root,
            Map<EClassId, TypedEClassRecord> classes,
            CoherentWitnessFamily witnesses,
            List<? extends FiniteUnfoldingTree> unfoldings,
            Map<String, ? extends List<? extends ContainerLawDeclaration>> containerLaws,
            List<? extends FlatConstructionCertificate> flatConstructions,
            List<? extends ContainerConstructionCertificate> containerConstructions,
            ConstructionSourceLedger constructionSources,
            SemanticProfile semanticProfile) {
        this(
                root,
                classes,
                witnesses,
                unfoldings,
                containerLaws,
                flatConstructions,
                containerConstructions,
                Collections.emptyList(),
                Collections.emptyList(),
                constructionSources,
                semanticProfile);
    }

    CertifiedSemanticArtifact(
            TypedInvocation root,
            Map<EClassId, TypedEClassRecord> classes,
            CoherentWitnessFamily witnesses,
            List<? extends FiniteUnfoldingTree> unfoldings,
            Map<String, ? extends List<? extends ContainerLawDeclaration>> containerLaws,
            List<? extends FlatConstructionCertificate> flatConstructions,
            List<? extends ContainerConstructionCertificate> containerConstructions,
            List<? extends DependentChainCertificate> dependentChainConstructions,
            ConstructionSourceLedger constructionSources,
            SemanticProfile semanticProfile) {
        this(
                root,
                classes,
                witnesses,
                unfoldings,
                containerLaws,
                flatConstructions,
                containerConstructions,
                dependentChainConstructions,
                Collections.emptyList(),
                constructionSources,
                semanticProfile);
    }

    CertifiedSemanticArtifact(
            TypedInvocation root,
            Map<EClassId, TypedEClassRecord> classes,
            CoherentWitnessFamily witnesses,
            List<? extends FiniteUnfoldingTree> unfoldings,
            Map<String, ? extends List<? extends ContainerLawDeclaration>> containerLaws,
            List<? extends FlatConstructionCertificate> flatConstructions,
            List<? extends ContainerConstructionCertificate> containerConstructions,
            List<? extends DependentChainCertificate> dependentChainConstructions,
            List<? extends CallOccurrenceCertificate> callOccurrenceCertificates,
            ConstructionSourceLedger constructionSources,
            SemanticProfile semanticProfile) {
        this.root = Objects.requireNonNull(root, "root");
        this.semanticProfile = Objects.requireNonNull(semanticProfile, "semanticProfile");
        Objects.requireNonNull(classes, "classes");
        this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        this.witnesses = Objects.requireNonNull(witnesses, "witnesses");
        Objects.requireNonNull(unfoldings, "unfoldings");
        List<FiniteUnfoldingTree> copied = new ArrayList<>(unfoldings.size());
        for (FiniteUnfoldingTree unfolding : unfoldings) {
            FiniteUnfoldingTree checked = Objects.requireNonNull(unfolding, "unfolding");
            if (!root.equals(checked.rootInvocation())) {
                throw new IllegalArgumentException(
                        "Every semantic observation must unfold the artifact root");
            }
            copied.add(checked);
        }
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(
                    "A certified semantic artifact requires at least one complete unfolding");
        }
        this.unfoldings = Collections.unmodifiableList(copied);

        Objects.requireNonNull(containerLaws, "containerLaws");
        Map<String, List<ContainerLawDeclaration>> copiedLaws = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends List<? extends ContainerLawDeclaration>> entry
                : containerLaws.entrySet()) {
            String operator = Objects.requireNonNull(entry.getKey(), "container operator");
            if (operator.trim().isEmpty()) {
                throw new IllegalArgumentException("Container operator must not be blank");
            }
            List<ContainerLawDeclaration> declarations = new ArrayList<>();
            for (ContainerLawDeclaration declaration : Objects.requireNonNull(
                    entry.getValue(), "container declarations")) {
                ContainerLawDeclaration checked = Objects.requireNonNull(
                        declaration, "container declaration");
                checked.requireCertified();
                if (checked.kind() == ContainerLawDeclaration.Kind.NONE) {
                    throw new IllegalArgumentException(
                            "A certified container registry cannot contain NONE");
                }
                for (ContainerLawCertificate certificate
                        : checked.certificates().values()) {
                    if (certificate.authority()
                                    != ContainerLawCertificate.Authority.ALLOY_PROFILE_THEORY
                            || !semanticProfile.equals(certificate.semanticProfile())
                            || !operator.equals(certificate.operatorIdentity())) {
                        throw new IllegalArgumentException(
                                "A production semantic artifact contains replayable "
                                        + "container-law evidence");
                    }
                }
                declarations.add(checked);
            }
            if (declarations.isEmpty()) {
                throw new IllegalArgumentException(
                        "A certified container registry entry cannot be empty");
            }
            copiedLaws.put(operator, Collections.unmodifiableList(declarations));
        }
        this.containerLaws = Collections.unmodifiableMap(copiedLaws);
        Objects.requireNonNull(flatConstructions, "flatConstructions");
        List<FlatConstructionCertificate> copiedConstructions = new ArrayList<>();
        for (FlatConstructionCertificate construction : flatConstructions) {
            FlatConstructionCertificate checked = Objects.requireNonNull(
                    construction, "flat construction");
            if (!semanticProfile.equals(checked.semanticProfile())) {
                throw new IllegalArgumentException(
                        "A flat construction uses another semantic profile");
            }
            CertificateVerifier.verify(checked);
            copiedConstructions.add(checked);
        }
        this.flatConstructions = Collections.unmodifiableList(copiedConstructions);
        Objects.requireNonNull(containerConstructions, "containerConstructions");
        List<ContainerConstructionCertificate> copiedContainerConstructions =
                new ArrayList<>();
        for (ContainerConstructionCertificate construction : containerConstructions) {
            ContainerConstructionCertificate checked = Objects.requireNonNull(
                    construction, "container construction");
            if (!semanticProfile.equals(checked.semanticProfile())) {
                throw new IllegalArgumentException(
                        "A container construction uses another semantic profile");
            }
            CertificateVerifier.verify(checked);
            copiedContainerConstructions.add(checked);
        }
        this.containerConstructions = Collections.unmodifiableList(
                copiedContainerConstructions);
        Objects.requireNonNull(
                dependentChainConstructions, "dependentChainConstructions");
        List<DependentChainCertificate> copiedDependentChains = new ArrayList<>();
        for (DependentChainCertificate construction : dependentChainConstructions) {
            DependentChainCertificate checked = Objects.requireNonNull(
                    construction, "dependent chain construction");
            if (!semanticProfile.equals(checked.semanticProfile())) {
                throw new IllegalArgumentException(
                        "A dependent-chain construction uses another semantic profile");
            }
            CertificateVerifier.verify(checked);
            copiedDependentChains.add(checked);
        }
        this.dependentChainConstructions = Collections.unmodifiableList(
                copiedDependentChains);
        Objects.requireNonNull(callOccurrenceCertificates, "callOccurrenceCertificates");
        List<CallOccurrenceCertificate> copiedCallOccurrences = new ArrayList<>();
        Set<Long> occurrenceIds = new LinkedHashSet<>();
        Set<String> occurrencePaths = new LinkedHashSet<>();
        for (CallOccurrenceCertificate occurrence : callOccurrenceCertificates) {
            CallOccurrenceCertificate checked = Objects.requireNonNull(
                    occurrence, "call occurrence certificate");
            if (!occurrenceIds.add(checked.occurrenceId())) {
                throw new IllegalArgumentException(
                        "Two CALL source occurrences use one parser occurrence id");
            }
            if (!occurrencePaths.add(checked.sourcePath())) {
                throw new IllegalArgumentException(
                        "Two CALL source occurrences use one deterministic source path");
            }
            copiedCallOccurrences.add(checked);
        }
        copiedCallOccurrences.sort(java.util.Comparator.comparing(
                value -> value.structuralKey().stableString()));
        this.callOccurrenceCertificates = Collections.unmodifiableList(
                copiedCallOccurrences);
        this.constructionSources = Objects.requireNonNull(
                constructionSources, "constructionSources");
        if (!semanticProfile.equals(this.constructionSources.semanticProfile())) {
            throw new IllegalArgumentException(
                    "The source-construction ledger uses another semantic profile");
        }
        List<BinderOccurrenceAutomorphismCertificate> occurrenceCertificates =
                new ArrayList<>();
        for (TypedEClassRecord record : this.classes.values()) {
            for (CanonicalShape shape : record.shapeWitnesses().keySet()) {
                for (BinderOccurrenceAutomorphismCertificate certificate
                        : BinderOccurrenceProofs.collect(shape.node())) {
                    CertificateVerifier.verify(certificate);
                    occurrenceCertificates.add(certificate);
                }
            }
        }
        this.binderOccurrenceCertificates = Collections.unmodifiableList(
                occurrenceCertificates);
        validateStoredNodeTheory();
        validateConstructionCoverage();
    }

    public TypedInvocation root() {
        return root;
    }

    public Map<EClassId, TypedEClassRecord> classes() {
        return classes;
    }

    public CoherentWitnessFamily witnesses() {
        return witnesses;
    }

    public List<FiniteUnfoldingTree> unfoldings() {
        return unfoldings;
    }

    /** All source operator laws admitted with signature certificates. */
    public Map<String, List<ContainerLawDeclaration>> containerLaws() {
        return containerLaws;
    }

    public SemanticProfile semanticProfile() {
        return semanticProfile;
    }

    public List<FlatConstructionCertificate> flatConstructions() {
        return flatConstructions;
    }

    public List<ContainerConstructionCertificate> containerConstructions() {
        return containerConstructions;
    }

    public List<DependentChainCertificate> dependentChainConstructions() {
        return dependentChainConstructions;
    }

    public List<CallOccurrenceCertificate> callOccurrenceCertificates() {
        return callOccurrenceCertificates;
    }

    public ConstructionSourceLedger constructionSources() {
        return constructionSources;
    }

    public List<BinderOccurrenceAutomorphismCertificate>
            binderOccurrenceCertificates() {
        return binderOccurrenceCertificates;
    }

    /**
     * Revalidates replacement flat evidence against this artifact's immutable
     * adapter-collected source ledger. The caller cannot replace that ledger.
     */
    public CertifiedSemanticArtifact withFlatConstructions(
            List<? extends FlatConstructionCertificate> replacements) {
        return new CertifiedSemanticArtifact(
                root,
                classes,
                witnesses,
                unfoldings,
                containerLaws,
                Objects.requireNonNull(replacements, "replacements"),
                containerConstructions,
                dependentChainConstructions,
                callOccurrenceCertificates,
                constructionSources,
                semanticProfile);
    }

    /**
     * Revalidates replacement nonflat evidence against this artifact's
     * immutable adapter-collected source ledger.
     */
    public CertifiedSemanticArtifact withContainerConstructions(
            List<? extends ContainerConstructionCertificate> replacements) {
        return new CertifiedSemanticArtifact(
                root,
                classes,
                witnesses,
                unfoldings,
                containerLaws,
                flatConstructions,
                Objects.requireNonNull(replacements, "replacements"),
                dependentChainConstructions,
                callOccurrenceCertificates,
                constructionSources,
                semanticProfile);
    }

    public CertifiedSemanticArtifact withDependentChainConstructions(
            List<? extends DependentChainCertificate> replacements) {
        return new CertifiedSemanticArtifact(
                root,
                classes,
                witnesses,
                unfoldings,
                containerLaws,
                flatConstructions,
                containerConstructions,
                Objects.requireNonNull(replacements, "replacements"),
                callOccurrenceCertificates,
                constructionSources,
                semanticProfile);
    }

    public CertifiedSemanticArtifact withCallOccurrenceCertificates(
            List<? extends CallOccurrenceCertificate> replacements) {
        return new CertifiedSemanticArtifact(
                root,
                classes,
                witnesses,
                unfoldings,
                containerLaws,
                flatConstructions,
                containerConstructions,
                dependentChainConstructions,
                Objects.requireNonNull(replacements, "replacements"),
                constructionSources,
                semanticProfile);
    }

    private void validateConstructionCoverage() {
        Set<StructuralKey> flatTargets = new LinkedHashSet<>();
        Map<StructuralKey, Integer> suppliedFlatSources = new LinkedHashMap<>();
        for (FlatConstructionCertificate construction : flatConstructions) {
            increment(suppliedFlatSources, construction.leftEndpoint().structuralKey());
            requireRegisteredConstruction(
                    construction.source().operator(), construction.path());
            if (construction.collapsedToSingleton()) {
                requireReachableSingletonTarget(construction.singletonTarget());
            } else {
                flatTargets.add(witnesses.canonicalShapeOf(
                        construction.target()).structuralKey());
            }
        }
        Set<StructuralKey> containerTargets = new LinkedHashSet<>();
        Map<StructuralKey, Integer> suppliedContainerSources = new LinkedHashMap<>();
        for (ContainerConstructionCertificate construction : containerConstructions) {
            increment(
                    suppliedContainerSources,
                    construction.leftEndpoint().structuralKey());
            requireRegisteredConstruction(
                    construction.operator(), construction.path());
            containerTargets.add(witnesses.canonicalShapeOf(
                    construction.target()).structuralKey());
        }
        if (!constructionSources.flatSources().equals(suppliedFlatSources)) {
            throw new IllegalArgumentException(
                    "Flat source occurrences and concrete evidence differ: required="
                            + constructionSources.flatSources()
                            + ", supplied=" + suppliedFlatSources);
        }
        if (!constructionSources.containerSources().equals(suppliedContainerSources)) {
            throw new IllegalArgumentException(
                    "Nonflat source occurrences and concrete evidence differ: required="
                            + constructionSources.containerSources()
                            + ", supplied=" + suppliedContainerSources);
        }
        Set<StructuralKey> dependentTargets = new LinkedHashSet<>();
        Map<StructuralKey, Integer> suppliedDependentSources = new LinkedHashMap<>();
        for (DependentChainCertificate construction : dependentChainConstructions) {
            increment(
                    suppliedDependentSources,
                    construction.leftEndpoint().structuralKey());
            dependentTargets.add(witnesses.canonicalShapeOf(
                    construction.target()).structuralKey());
        }
        if (!constructionSources.dependentChainSources().equals(
                suppliedDependentSources)) {
            throw new IllegalArgumentException(
                    "Dependent-chain source occurrences and evidence differ: required="
                            + constructionSources.dependentChainSources()
                            + ", supplied=" + suppliedDependentSources);
        }
        Map<StructuralKey, Integer> suppliedCallSources = new LinkedHashMap<>();
        for (CallOccurrenceCertificate occurrence : callOccurrenceCertificates) {
            increment(suppliedCallSources, occurrence.structuralKey());
        }
        if (!constructionSources.callSources().equals(suppliedCallSources)) {
            throw new IllegalArgumentException(
                    "CALL source occurrences and evidence differ: required="
                            + constructionSources.callSources()
                            + ", supplied=" + suppliedCallSources);
        }
        Set<StructuralKey> requiredFlat = new LinkedHashSet<>();
        Set<StructuralKey> requiredContainers = new LinkedHashSet<>();
        Set<StructuralKey> requiredDependentChains = new LinkedHashSet<>();
        Set<String> requiredCalls = new LinkedHashSet<>();
        for (TypedEClassRecord record : classes.values()) {
            for (CanonicalShape shape : record.shapeWitnesses().keySet()) {
                TypedENode node = shape.node();
                if (isCallNode(node)) {
                    requiredCalls.add(node.operator().operator());
                } else if (node.operator().usesFlatConstruction()) {
                    requiredFlat.add(shape.structuralKey());
                } else if (isDependentChainNode(node)) {
                    requiredDependentChains.add(shape.structuralKey());
                } else if (!node.operator().containerLaws().isEmpty()) {
                    requiredContainers.add(shape.structuralKey());
                }
            }
        }
        if (!requiredFlat.equals(flatTargets)) {
            throw new IllegalArgumentException(
                    "Stored flat occurrences and exact source-law replay targets differ: "
                            + "required=" + requiredFlat + ", supplied=" + flatTargets);
        }
        if (!requiredContainers.equals(containerTargets)) {
            throw new IllegalArgumentException(
                    "Stored nonflat container occurrences and exact source-law replay targets differ: "
                            + "required=" + requiredContainers
                            + ", supplied=" + containerTargets);
        }
        if (!requiredDependentChains.equals(dependentTargets)) {
            throw new IllegalArgumentException(
                    "Stored dependent chains and exact type-proof targets differ: required="
                            + requiredDependentChains + ", supplied=" + dependentTargets);
        }
        Set<String> suppliedCalls = callOccurrenceCertificates.stream()
                .map(certificate -> certificate.sourceEndpoint()
                        .operator().operator())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!requiredCalls.equals(suppliedCalls)) {
            throw new IllegalArgumentException(
                    "Stored CALL terms and source-occurrence evidence differ: required="
                            + requiredCalls + ", supplied=" + suppliedCalls);
        }
    }

    private static boolean isCallNode(TypedENode node) {
        return node.operator().operator().startsWith("ALLOY/CALL/");
    }

    private static boolean isDependentChainNode(TypedENode node) {
        if (!node.operator().operator().startsWith("ALLOY/DEPENDENT-CHAIN/")
                || node.ports().size() != 1
                || !(node.ports().get(0) instanceof SeqPort)) {
            return false;
        }
        return ((SeqPort) node.ports().get(0)).schema().isDependent();
    }

    private static void increment(Map<StructuralKey, Integer> counts, StructuralKey key) {
        counts.put(key, Math.incrementExact(counts.getOrDefault(key, 0)));
    }

    private void requireReachableSingletonTarget(OnePort target) {
        if (target.leaf() instanceof InvocationPortLeaf) {
            TypedInvocation invocation = ((InvocationPortLeaf) target.leaf()).invocation();
            TypedEClassRecord record = classes.get(invocation.eclass().id());
            if (record == null
                    || !record.interfaceView().equals(invocation.eclass())) {
                throw new IllegalArgumentException(
                        "A singleton collapse targets an unknown or mismatched e-class occurrence");
            }
        }
    }

    private void requireRegisteredConstruction(
            InstantiatedOperator operator, PortPath path) {
        List<ContainerLawDeclaration> registered = containerLaws.get(operator.operator());
        if (registered == null) {
            throw new IllegalArgumentException(
                    "Concrete construction uses an unregistered operator theory");
        }
        ContainerLawDeclaration declaration = operator.lawForPath(path);
        for (ContainerLawCertificate certificate : declaration.certificates().values()) {
            boolean found = registered.stream()
                    .flatMap(candidate -> candidate.certificates().values().stream())
                    .anyMatch(candidate -> candidate.lawIndex().equals(
                            certificate.lawIndex()));
            if (!found) {
                throw new IllegalArgumentException(
                        "Concrete construction uses unregistered exact law evidence");
            }
        }
    }

    private void validateStoredNodeTheory() {
        Map<String, java.util.Set<StructuralKey>> registry = new LinkedHashMap<>();
        for (Map.Entry<String, List<ContainerLawDeclaration>> entry
                : containerLaws.entrySet()) {
            java.util.Set<StructuralKey> indices = new java.util.LinkedHashSet<>();
            for (ContainerLawDeclaration declaration : entry.getValue()) {
                for (ContainerLawCertificate certificate
                        : declaration.certificates().values()) {
                    indices.add(certificate.lawIndex());
                }
            }
            registry.put(entry.getKey(), indices);
        }
        for (TypedEClassRecord record : classes.values()) {
            for (CanonicalShape shape : record.shapeWitnesses().keySet()) {
                TypedENode node = shape.node();
                CertificateVerifier.requireProductionNodeTheory(node, semanticProfile);
                for (ContainerLawDeclaration declaration
                        : node.operator().containerLaws().values()) {
                    for (ContainerLawCertificate certificate
                            : declaration.certificates().values()) {
                        java.util.Set<StructuralKey> registered = registry.get(
                                certificate.operatorIdentity());
                        if (registered == null
                                || !registered.contains(certificate.lawIndex())) {
                            throw new IllegalArgumentException(
                                    "Stored node theory is absent from the artifact law registry");
                        }
                    }
                }
            }
        }
    }
}
