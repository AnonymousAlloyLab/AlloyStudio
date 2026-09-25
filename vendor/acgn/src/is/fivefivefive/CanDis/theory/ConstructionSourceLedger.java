package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable occurrence ledger collected from source syntax before normalization. */
public final class ConstructionSourceLedger {
    private final SemanticProfile semanticProfile;
    private final Map<StructuralKey, Integer> flatSources;
    private final Map<StructuralKey, Integer> containerSources;
    private final Map<StructuralKey, Integer> dependentChainSources;
    private final Map<StructuralKey, Integer> callSources;

    private ConstructionSourceLedger(
            SemanticProfile semanticProfile,
            Map<StructuralKey, Integer> flatSources,
            Map<StructuralKey, Integer> containerSources,
            Map<StructuralKey, Integer> dependentChainSources,
            Map<StructuralKey, Integer> callSources) {
        this.semanticProfile = Objects.requireNonNull(semanticProfile, "semanticProfile");
        this.flatSources = immutableCounts(flatSources);
        this.containerSources = immutableCounts(containerSources);
        this.dependentChainSources = immutableCounts(dependentChainSources);
        this.callSources = immutableCounts(callSources);
    }

    static Builder builder(SemanticProfile semanticProfile) {
        return new Builder(semanticProfile);
    }

    static ConstructionSourceLedger empty(SemanticProfile semanticProfile) {
        return builder(semanticProfile).build();
    }

    public SemanticProfile semanticProfile() {
        return semanticProfile;
    }

    public Map<StructuralKey, Integer> flatSources() {
        return flatSources;
    }

    public Map<StructuralKey, Integer> containerSources() {
        return containerSources;
    }

    public Map<StructuralKey, Integer> dependentChainSources() {
        return dependentChainSources;
    }

    public Map<StructuralKey, Integer> callSources() {
        return callSources;
    }

    private static Map<StructuralKey, Integer> immutableCounts(
            Map<StructuralKey, Integer> source) {
        Map<StructuralKey, Integer> copied = new LinkedHashMap<>();
        for (Map.Entry<StructuralKey, Integer> entry : source.entrySet()) {
            int count = Objects.requireNonNull(entry.getValue(), "source count");
            if (count <= 0) {
                throw new IllegalArgumentException(
                        "A construction-source count must be positive");
            }
            copied.put(Objects.requireNonNull(entry.getKey(), "source endpoint"), count);
        }
        return Collections.unmodifiableMap(copied);
    }

    private static void increment(Map<StructuralKey, Integer> counts, StructuralKey key) {
        counts.put(key, Math.incrementExact(counts.getOrDefault(key, 0)));
    }

    static final class Builder {
        private final SemanticProfile semanticProfile;
        private final Map<StructuralKey, Integer> flatSources = new LinkedHashMap<>();
        private final Map<StructuralKey, Integer> containerSources = new LinkedHashMap<>();
        private final Map<StructuralKey, Integer> dependentChainSources =
                new LinkedHashMap<>();
        private final Map<StructuralKey, Integer> callSources =
                new LinkedHashMap<>();

        private Builder(SemanticProfile semanticProfile) {
            this.semanticProfile = Objects.requireNonNull(
                    semanticProfile, "semanticProfile");
            if (!semanticProfile.isAdmissibleAlloyProfile()) {
                throw new IllegalArgumentException(
                        "A production source ledger requires an authorized Alloy profile");
            }
        }

        void recordFlat(FlatApplication source) {
            increment(
                    flatSources,
                    TypedCertificateEndpoint.flatApplication(
                            Objects.requireNonNull(source, "source"), semanticProfile)
                            .structuralKey());
        }

        void recordContainer(
                InstantiatedOperator operator,
                PortPath path,
                TypedSlotContext context,
                List<? extends PortValue> inputOccurrences) {
            increment(
                    containerSources,
                    TypedCertificateEndpoint.containerApplication(
                            Objects.requireNonNull(operator, "operator"),
                            Objects.requireNonNull(path, "path"),
                            Objects.requireNonNull(context, "context"),
                            Objects.requireNonNull(inputOccurrences, "inputOccurrences"),
                            semanticProfile)
                            .structuralKey());
        }

        void recordDependentChain(DependentChainApplication source) {
            recordDependentChain(
                    source,
                    StructuralKey.branch(
                            "dependent-chain-semantic-source-v1",
                            List.of(source.structuralKey())));
        }

        void recordDependentChain(
                DependentChainApplication source,
                StructuralKey sourceOccurrenceCommitment) {
            increment(
                    dependentChainSources,
                    TypedCertificateEndpoint.dependentChainApplication(
                            Objects.requireNonNull(source, "source"),
                            semanticProfile,
                            Objects.requireNonNull(
                                    sourceOccurrenceCommitment,
                                    "sourceOccurrenceCommitment"))
                            .structuralKey());
        }

        void recordCall(CallOccurrenceCertificate occurrence) {
            increment(
                    callSources,
                    Objects.requireNonNull(occurrence, "occurrence")
                            .structuralKey());
        }

        ConstructionSourceLedger build() {
            return new ConstructionSourceLedger(
                    semanticProfile,
                    flatSources,
                    containerSources,
                    dependentChainSources,
                    callSources);
        }
    }
}
