package is.fivefivefive.CanDis.augmentation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.theory.BagPort;
import is.fivefivefive.CanDis.theory.BindBlockPort;
import is.fivefivefive.CanDis.theory.BindPort;
import is.fivefivefive.CanDis.theory.CertificateProvenance;
import is.fivefivefive.CanDis.theory.CertifiedSemanticArtifact;
import is.fivefivefive.CanDis.theory.FiniteUnfoldingTree;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.OnePort;
import is.fivefivefive.CanDis.theory.PortValue;
import is.fivefivefive.CanDis.theory.SeqPort;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.SetPort;
import is.fivefivefive.CanDis.theory.StructuralKey;
import is.fivefivefive.CanDis.theory.TypedENode;

/**
 * Fail-closed adaptive equality overlay over immutable bootstrap theory R0.
 * Adaptive equalities are never silently converted into oriented rewrites.
 */
public final class EquivalenceAugmenter {
    public static final String VERSION =
            "certified-equivalence-augmenter-v5-closed-source-closure";

    public enum State {
        OBSERVED,
        CANDIDATE,
        FALSIFIED,
        VERIFIED_LOCAL,
        VERIFIED_SCHEMA,
        ADMITTED
    }

    public enum AdmissionScope {
        INSTANCE_LOCAL,
        GENERALIZED_SCHEMA
    }

    public enum Orientation {
        UNORIENTED
    }

    public enum GuardDimension {
        OPERATOR,
        EXACT_TYPE,
        ARITY,
        SEMANTIC_PROFILE,
        PROVENANCE
    }

    public record Limits(
            int maxObservations,
            int maxCandidates,
            int maxAdmittedSchemas,
            int maxTheoryGenerations,
            int maxSchemaAffectedPairs,
            int maxSchemaChecksPerComparison,
            int maxApplicationRecords,
            int maxCachedComparisons) {
        public Limits {
            if (maxObservations < 1
                    || maxCandidates < 1
                    || maxAdmittedSchemas < 1
                    || maxTheoryGenerations < 1
                    || maxSchemaAffectedPairs < 1
                    || maxSchemaChecksPerComparison < 1
                    || maxApplicationRecords < 1
                    || maxCachedComparisons < 1) {
                throw new IllegalArgumentException(
                        "Every augmentation growth bound must be positive");
            }
        }

        public static Limits conservativeDefaults() {
            return new Limits(
                    10_000, 1_000, 128, 128, 5_000, 256, 20_000, 50_000);
        }
    }

    public record WeakeningProbe(
            AlloyEquivalenceValidator.Request request,
            GraphType expectedLeftType,
            GraphType expectedRightType) {
        public WeakeningProbe {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(expectedLeftType, "expectedLeftType");
            Objects.requireNonNull(expectedRightType, "expectedRightType");
            if (request.expectedOutcome()
                    != AlloyEquivalenceValidator.ExpectedOutcome.COUNTEREXAMPLE_REQUIRED) {
                throw new IllegalArgumentException(
                        "A weakened guard probe must require an Alloy counterexample");
            }
        }
    }

    public record SchemaValidation(
            AlloyEquivalenceValidator.Request directAlloyValidation,
            Map<GuardDimension, WeakeningProbe> weakenedGuardProbes,
            LeanSchemaProofValidator.Request leanProof,
            List<String> dependencies) {
        public SchemaValidation {
            Objects.requireNonNull(directAlloyValidation, "directAlloyValidation");
            if (directAlloyValidation.expectedOutcome()
                    != AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE) {
                throw new IllegalArgumentException(
                        "Schema validation must directly seek absence of a counterexample");
            }
            weakenedGuardProbes = Collections.unmodifiableMap(
                    new EnumMap<>(Objects.requireNonNull(
                            weakenedGuardProbes, "weakenedGuardProbes")));
            Objects.requireNonNull(leanProof, "leanProof");
            dependencies = List.copyOf(dependencies);
        }
    }

    public record Evaluation(
            int distance,
            int bootstrapDistance,
            long theoryGeneration,
            Optional<AugmentationCertificate> augmentationCertificate,
            QuotientRepairDistance.Result bootstrapMetric) {
        public Evaluation {
            if (distance < 0 || bootstrapDistance < 0 || theoryGeneration < 0) {
                throw new IllegalArgumentException("Distances and generations are nonnegative");
            }
            Objects.requireNonNull(augmentationCertificate, "augmentationCertificate");
            Objects.requireNonNull(bootstrapMetric, "bootstrapMetric");
            if (bootstrapMetric.distance() != bootstrapDistance) {
                throw new IllegalArgumentException(
                        "Bootstrap metric and reported bootstrap distance disagree");
            }
            if (augmentationCertificate.isPresent()
                    != (distance == 0 && bootstrapDistance > 0)) {
                throw new IllegalArgumentException(
                        "Only a positive R0 miss may carry an adaptive zero certificate");
            }
        }

        public boolean adaptivelyEquivalent() {
            return distance == 0 && bootstrapDistance > 0;
        }
    }

    public static final class AugmentationCertificate {
        private final String endpointPair;
        private final String recordId;
        private final AdmissionScope scope;
        private final long theoryGeneration;
        private final String bootstrapDigest;
        private final String evidenceDigest;
        private final String correspondenceDigest;
        private final String semanticContextDigest;
        private final String digest;

        private AugmentationCertificate(
                String endpointPair,
                String recordId,
                AdmissionScope scope,
                long theoryGeneration,
                String bootstrapDigest,
                String evidenceDigest,
                String correspondenceDigest,
                String semanticContextDigest) {
            this.endpointPair = requireText(endpointPair, "endpointPair");
            this.recordId = requireText(recordId, "recordId");
            this.scope = Objects.requireNonNull(scope, "scope");
            if (theoryGeneration < 0) {
                throw new IllegalArgumentException("Theory generation must be nonnegative");
            }
            this.theoryGeneration = theoryGeneration;
            this.bootstrapDigest = requireDigest(bootstrapDigest, "bootstrapDigest");
            this.evidenceDigest = requireDigest(evidenceDigest, "evidenceDigest");
            this.correspondenceDigest = requireDigest(
                    correspondenceDigest, "correspondenceDigest");
            this.semanticContextDigest = requireDigest(
                    semanticContextDigest, "semanticContextDigest");
            this.digest = AugmentationDigests.sha256(String.join("\n",
                    VERSION,
                    endpointPair,
                    recordId,
                    scope.name(),
                    Long.toString(theoryGeneration),
                    bootstrapDigest,
                    evidenceDigest,
                    correspondenceDigest,
                    semanticContextDigest));
        }

        public String endpointPair() {
            return endpointPair;
        }

        public String recordId() {
            return recordId;
        }

        public AdmissionScope scope() {
            return scope;
        }

        public long theoryGeneration() {
            return theoryGeneration;
        }

        public String bootstrapDigest() {
            return bootstrapDigest;
        }

        public String evidenceDigest() {
            return evidenceDigest;
        }

        public String correspondenceDigest() {
            return correspondenceDigest;
        }

        public String semanticContextDigest() {
            return semanticContextDigest;
        }

        public String digest() {
            return digest;
        }
    }

    public record LocalRecordView(
            String id,
            State state,
            AdmissionScope scope,
            String endpointPair,
            String region,
            String leftInputIdentifier,
            String leftInputSha256,
            String rightInputIdentifier,
            String rightInputSha256,
            int observedDistance,
            String alloyOutcome,
            String alloyDetail,
            String alloyEvidenceDigest,
            String sourceCorrespondenceDigest,
            String sourceCorrespondenceDetail,
            String semanticContextDigest,
            List<State> transitions,
            long theoryGeneration) {
    }

    public record SchemaRecordView(
            String id,
            State state,
            AdmissionScope scope,
            String region,
            String schemaDigest,
            String leftPattern,
            String rightPattern,
            String semanticSchemaDigest,
            String leanTheoremParameters,
            String leanTheoremStatement,
            String guardsDigest,
            Map<GuardDimension, String> guards,
            List<String> originWitnesses,
            List<String> positiveEvidence,
            Map<GuardDimension, GuardEvidenceView> negativeEvidence,
            String leanProofReference,
            String leanProofDigest,
            String leanExecutablePath,
            String leanToolchainSha256,
            String leanVersion,
            List<String> dependencies,
            Orientation orientation,
            List<State> transitions,
            long theoryGeneration,
            int affectedPairs) {
    }

    public record GuardEvidenceView(
            String kind,
            String outcome,
            String digest,
            String detail) {
    }

    public record ApplicationRecordView(
            String certificateDigest,
            String endpointPair,
            String schemaId,
            String sourceCorrespondenceDigest,
            String sourceCorrespondenceDetail,
            String semanticContextDigest,
            long theoryGeneration) {
    }

    private final BootstrapTheoryR0 bootstrap;
    private final Limits limits;
    private final AlloyEquivalenceValidator alloyValidator;
    private final LeanSchemaProofValidator leanValidator;
    private final NavigableMap<String, LocalRecord> locals = new TreeMap<>();
    private final NavigableMap<String, SchemaRecord> schemas = new TreeMap<>();
    private final NavigableMap<String, List<String>> verifiedLocalPairIndex =
            new TreeMap<>();
    private final NavigableMap<String, List<String>> admittedByRegion = new TreeMap<>();
    private final NavigableMap<String, CachedDecision> comparisonCache = new TreeMap<>();
    private final NavigableMap<String, ApplicationRecord> applications = new TreeMap<>();
    private long generation;

    public EquivalenceAugmenter() {
        this(
                BootstrapTheoryR0.current(),
                Limits.conservativeDefaults(),
                new AlloyEquivalenceValidator(),
                new LeanSchemaProofValidator());
    }

    public EquivalenceAugmenter(
            BootstrapTheoryR0 bootstrap,
            Limits limits,
            AlloyEquivalenceValidator alloyValidator,
            LeanSchemaProofValidator leanValidator) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.alloyValidator = Objects.requireNonNull(alloyValidator, "alloyValidator");
        this.leanValidator = Objects.requireNonNull(leanValidator, "leanValidator");
    }

    public BootstrapTheoryR0 bootstrap() {
        return bootstrap;
    }

    public synchronized long theoryGeneration() {
        return generation;
    }

    /** Evaluates R0 first, then the exact/local and admitted-schema overlay. */
    public Evaluation evaluate(
            CanonicalAlloyPipeline.Prepared left,
            CanonicalAlloyPipeline.Prepared right) {
        return evaluate(left, right, null);
    }

    /** Schema-aware evaluation with parser-resolved application correspondence. */
    public Evaluation evaluate(
            CanonicalAlloyPipeline.Prepared left,
            CanonicalAlloyPipeline.Prepared right,
            AlloyEquivalenceValidator.Request application) {
        QuotientRepairDistance.Result base = CanonicalAlloyPipeline.distanceEvaluation(
                left, right);
        if (base.distance() == 0) {
            return new Evaluation(0, 0, theoryGeneration(), Optional.empty(), base);
        }
        Operand leftOperand = Operand.fromPrepared(left);
        Operand rightOperand = Operand.fromPrepared(right);
        SourceEndpointCorrespondence.Evidence applicationCorrespondence = null;
        AlloyBooleanSchema.Pair semanticApplication = null;
        String applicationContextDigest = null;
        if (application != null) {
            if (application.expectedOutcome()
                    != AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE) {
                throw new IllegalArgumentException(
                        "An equality application must request no Alloy counterexample");
            }
            alloyValidator.requireSemanticContext(
                    application, leftOperand.semanticProfile);
            applicationCorrespondence = SourceEndpointCorrespondence.verify(
                    application,
                    left,
                    right,
                    leftOperand.inputSha256,
                    rightOperand.inputSha256);
            applicationContextDigest = semanticContextDigest(
                    application, applicationCorrespondence);
            try {
                semanticApplication = AlloyBooleanSchema.analyze(application);
            } catch (IllegalArgumentException unsupportedSchemaShape) {
                semanticApplication = null;
            }
        }
        Decision decision;
        synchronized (this) {
            decision = adaptiveDecision(
                    leftOperand,
                    rightOperand,
                    semanticApplication,
                    applicationCorrespondence,
                    applicationContextDigest);
        }
        return decision == null
                ? new Evaluation(
                        base.distance(), base.distance(), theoryGeneration(),
                        Optional.empty(), base)
                : new Evaluation(
                        0, base.distance(), decision.certificate.theoryGeneration(),
                        Optional.of(decision.certificate), base);
    }

    /**
     * In-flight path for a caller that has identified a semantic-equivalence
     * obligation. A positive miss is persisted and checked before the exact
     * local overlay can affect the returned metric.
     */
    public Evaluation evaluateAndObserveEquivalent(
            CanonicalAlloyPipeline.Prepared left,
            CanonicalAlloyPipeline.Prepared right,
            AlloyEquivalenceValidator.Request validation) {
        Evaluation prior = evaluate(left, right);
        if (prior.distance() == 0) {
            return prior;
        }
        LocalRecordView observed = observeEquivalent(left, right, validation);
        return observed.state() == State.VERIFIED_LOCAL
                ? evaluate(left, right, validation)
                : prior;
    }

    /**
     * Records and directly validates one exact, scope-bound semantic miss.
     * Counterexamples falsify it; timeout/error evidence remains retryable.
     */
    public LocalRecordView observeEquivalent(
            CanonicalAlloyPipeline.Prepared left,
            CanonicalAlloyPipeline.Prepared right,
            AlloyEquivalenceValidator.Request validation) {
        Objects.requireNonNull(validation, "validation");
        if (validation.expectedOutcome()
                != AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE) {
            throw new IllegalArgumentException(
                    "An observed equivalence must request no Alloy counterexample");
        }
        QuotientRepairDistance.Result base = CanonicalAlloyPipeline.distanceEvaluation(
                left, right);
        if (base.distance() == 0) {
            throw new IllegalArgumentException(
                    "R0 already equates this pair; it is not augmentation data");
        }
        Operand leftOperand = Operand.fromPrepared(left);
        Operand rightOperand = Operand.fromPrepared(right);
        alloyValidator.requireSemanticContext(validation, leftOperand.semanticProfile);
        SourceEndpointCorrespondence.Evidence correspondence =
                SourceEndpointCorrespondence.verify(
                        validation,
                        left,
                        right,
                        leftOperand.inputSha256,
                        rightOperand.inputSha256);
        AlloyBooleanSchema.Pair semanticPair;
        try {
            semanticPair = AlloyBooleanSchema.analyze(validation);
        } catch (IllegalArgumentException unsupportedForSchema) {
            semanticPair = null;
        }
        String contextDigest = semanticContextDigest(validation, correspondence);
        LocalRecord record;
        synchronized (this) {
            String pair = PairKey.of(leftOperand, rightOperand).stable;
            String id = "local-" + AugmentationDigests.sha256(
                    pair + "\n" + contextDigest).substring(0, 24);
            LocalRecord prior = locals.get(id);
            if (prior != null) {
                if (prior.validationInFlight) {
                    throw new IllegalStateException(
                            "Local validation is already in flight: " + prior.id);
                }
                if (prior.state != State.CANDIDATE) {
                    return prior.view();
                }
                prior.validationInFlight = true;
                record = prior;
            } else {
                if (locals.size() >= limits.maxObservations()) {
                    throw new IllegalStateException(
                            "Adaptive observation budget exhausted");
                }
                record = new LocalRecord(
                        id,
                        leftOperand,
                        rightOperand,
                        PairKey.of(leftOperand, rightOperand),
                        RegionPair.of(leftOperand, rightOperand),
                        validation,
                        semanticPair,
                        correspondence,
                        contextDigest,
                        base.distance(),
                        generation);
                record.validationInFlight = true;
                locals.put(id, record);
                record.transition(State.CANDIDATE);
            }
        }

        AlloyEquivalenceValidator.Evidence evidence;
        try {
            evidence = alloyValidator.validate(
                    validation,
                    leftOperand.outputType,
                    rightOperand.outputType,
                    leftOperand.semanticProfile);
        } catch (RuntimeException exception) {
            synchronized (this) {
                record.validationInFlight = false;
            }
            throw exception;
        }
        synchronized (this) {
            record.alloyEvidence = evidence;
            record.validationInFlight = false;
            if (!evidence.validatesExpectedOutcome()) {
                if (evidence.outcome()
                        == AlloyEquivalenceValidator.Outcome.COUNTEREXAMPLE) {
                    record.transition(State.FALSIFIED);
                }
                return record.view();
            }
            record.transition(State.VERIFIED_LOCAL);
            verifiedLocalPairIndex.computeIfAbsent(
                    record.pair.stable, ignored -> new ArrayList<>()).add(record.id);
            invalidatePair(record.pair.stable);
            return record.view();
        }
    }

    /** Proposes the least-general guarded schema shared by two or more local misses. */
    public synchronized SchemaRecordView proposeSchema(
            Collection<String> localWitnessIds) {
        Objects.requireNonNull(localWitnessIds, "localWitnessIds");
        if (schemas.size() >= limits.maxCandidates()) {
            throw new IllegalStateException("Adaptive schema candidate budget exhausted");
        }
        List<LocalRecord> witnesses = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String id : localWitnessIds) {
            if (!unique.add(requireText(id, "local witness id"))) {
                continue;
            }
            LocalRecord witness = locals.get(id);
            if (witness == null || witness.state != State.VERIFIED_LOCAL) {
                throw new IllegalArgumentException(
                        "A schema witness must be an existing VERIFIED_LOCAL record: " + id);
            }
            witnesses.add(witness);
        }
        if (witnesses.size() < 2) {
            throw new IllegalArgumentException(
                    "Never infer a global schema from a single observed pair");
        }
        if (witnesses.stream().map(witness -> witness.pair.stable).distinct().count() < 2) {
            throw new IllegalArgumentException(
                    "A global schema requires two distinct observed endpoint pairs");
        }
        witnesses.sort(Comparator.comparing(record -> record.id));
        RegionPair region = witnesses.get(0).region;
        for (LocalRecord witness : witnesses) {
            if (!region.equals(witness.region)) {
                throw new IllegalArgumentException(
                        "Schema witnesses must share exact operator/type/arity/profile guards");
            }
        }

        List<StructuralAntiUnifier.Equation> equations = new ArrayList<>();
        List<StructuralAntiUnifier.Equation> semanticEquations = new ArrayList<>();
        for (LocalRecord witness : witnesses) {
            if (witness.semanticPair == null) {
                throw new IllegalArgumentException(
                        "A generalized schema requires parser-resolved Boolean "
                                + "correspondence for every local witness");
            }
            StructuralKey equationLeft = witness.left.term;
            StructuralKey equationRight = witness.right.term;
            StructuralKey semanticLeft = witness.semanticPair.left();
            StructuralKey semanticRight = witness.semanticPair.right();
            OperandRegion witnessLeftRegion = OperandRegion.of(witness.left);
            OperandRegion witnessRightRegion = OperandRegion.of(witness.right);
            boolean swap = !witnessLeftRegion.stable.equals(region.left.stable)
                    && witnessRightRegion.stable.equals(region.left.stable);
            if (!swap
                    && witnessLeftRegion.stable.equals(witnessRightRegion.stable)
                    && equationLeft.compareTo(equationRight) > 0) {
                swap = true;
            }
            if (swap) {
                StructuralKey temporary = equationLeft;
                equationLeft = equationRight;
                equationRight = temporary;
                temporary = semanticLeft;
                semanticLeft = semanticRight;
                semanticRight = temporary;
            }
            equations.add(new StructuralAntiUnifier.Equation(
                    witness.id, equationLeft, equationRight));
            semanticEquations.add(new StructuralAntiUnifier.Equation(
                    witness.id, semanticLeft, semanticRight));
        }
        StructuralAntiUnifier.Proposal proposal = StructuralAntiUnifier.propose(equations);
        StructuralAntiUnifier.Proposal semanticProposal =
                StructuralAntiUnifier.propose(semanticEquations);
        Set<String> minimalOriginIds = new LinkedHashSet<>(proposal.witnessIds());
        minimalOriginIds.addAll(semanticProposal.witnessIds());
        List<LocalRecord> minimized = new ArrayList<>();
        for (String id : minimalOriginIds.stream().sorted().toList()) {
            minimized.add(locals.get(id));
        }
        if (minimized.stream().map(witness -> witness.pair.stable).distinct().count() < 2) {
            throw new IllegalArgumentException(
                    "Schema minimization cannot reduce evidence to one endpoint pair");
        }
        GuardSet guards = GuardSet.from(region);
        String directionlessPattern = directionless(
                proposal.left().stableForm(), proposal.right().stableForm());
        String id = "schema-" + AugmentationDigests.sha256(String.join("\n",
                bootstrap.digest(),
                region.stable,
                directionlessPattern,
                semanticProposal.digest(),
                guards.digest)).substring(0, 24);
        SchemaRecord existing = schemas.get(id);
        if (existing != null) {
            return existing.view();
        }
        SchemaRecord schema = new SchemaRecord(
                id,
                region,
                proposal,
                semanticProposal,
                guards,
                minimized,
                generation);
        schemas.put(id, schema);
        return schema.view();
    }

    /**
     * Validates a candidate but does not orient or globally admit it. Admission
     * is a separate explicit transition.
     */
    public SchemaRecordView verifySchema(
            String schemaId,
            SchemaValidation validation) throws IOException {
        Objects.requireNonNull(validation, "validation");
        SchemaRecord schema;
        synchronized (this) {
            schema = requireSchema(schemaId, State.CANDIDATE);
            requireSchemaDependencies(schema, validation.dependencies());
            if (!validation.leanProof().dependencies().equals(
                    validation.dependencies())) {
                throw new IllegalArgumentException(
                        "Lean proof dependencies must exactly equal schema dependencies");
            }
            if (!validation.leanProof().schemaDigest().equals(schema.schemaDigest)) {
                throw new IllegalArgumentException(
                        "Lean proof request is bound to another inferred schema");
            }
            AlloyBooleanSchema.Pair directSemantic = AlloyBooleanSchema.analyze(
                    validation.directAlloyValidation());
            if (!schema.semanticProposal.matchesEitherDirection(
                    directSemantic.left(), directSemantic.right())) {
                throw new IllegalArgumentException(
                        "Direct Alloy validation does not instantiate the inferred "
                                + "semantic schema");
            }
            if (!validation.leanProof().theoremParameters().equals(
                            schema.leanObligation.parameters())
                    || !validation.leanProof().theoremStatement().equals(
                            schema.leanObligation.statement())) {
                throw new IllegalArgumentException(
                        "Lean theorem is not the mechanically derived Alloy schema obligation");
            }
            EnumSet<GuardDimension> required = EnumSet.of(
                    GuardDimension.OPERATOR,
                    GuardDimension.EXACT_TYPE,
                    GuardDimension.ARITY);
            if (!validation.weakenedGuardProbes().keySet().equals(required)) {
                throw new IllegalArgumentException(
                        "Counterexample evidence must cover operator, exact type, and arity "
                                + "guard weakening exactly once");
            }
            requireMechanicallyWeakenedGuards(schema, validation);
            if (schema.verificationInFlight) {
                throw new IllegalStateException(
                        "Schema validation is already in flight: " + schema.id);
            }
            schema.verificationInFlight = true;
        }

        LocalRecord first = schema.witnesses.get(0);
        AlloyEquivalenceValidator.Evidence direct;
        try {
            direct = alloyValidator.validate(
                    validation.directAlloyValidation(),
                    first.left.outputType,
                    first.right.outputType,
                    first.left.semanticProfile);
        } catch (RuntimeException exception) {
            synchronized (this) {
                schema.verificationInFlight = false;
            }
            throw exception;
        }
        if (!direct.validatesExpectedOutcome()) {
            synchronized (this) {
                schema.directAlloyEvidence = direct;
                schema.verificationInFlight = false;
                if (direct.outcome()
                        == AlloyEquivalenceValidator.Outcome.COUNTEREXAMPLE) {
                    schema.transition(State.FALSIFIED);
                }
                return schema.view();
            }
        }

        EnumMap<GuardDimension, GuardEvidence> negative =
                new EnumMap<>(GuardDimension.class);
        for (Map.Entry<GuardDimension, WeakeningProbe> entry
                : validation.weakenedGuardProbes().entrySet()) {
            WeakeningProbe probe = entry.getValue();
            AlloyEquivalenceValidator.Evidence evidence;
            try {
                evidence = alloyValidator.validate(
                        probe.request(),
                        probe.expectedLeftType(),
                        probe.expectedRightType(),
                        first.left.semanticProfile);
            } catch (RuntimeException exception) {
                synchronized (this) {
                    schema.directAlloyEvidence = direct;
                    schema.negativeEvidence.putAll(negative);
                    schema.verificationInFlight = false;
                }
                throw exception;
            }
            negative.put(entry.getKey(), GuardEvidence.alloy(evidence));
            if (!evidence.validatesExpectedOutcome()) {
                synchronized (this) {
                    schema.directAlloyEvidence = direct;
                    schema.negativeEvidence.putAll(negative);
                    schema.verificationInFlight = false;
                    return schema.view();
                }
            }
        }
        LeanSchemaProofValidator.Evidence lean;
        try {
            lean = leanValidator.verify(validation.leanProof());
        } catch (IOException | RuntimeException exception) {
            synchronized (this) {
                schema.directAlloyEvidence = direct;
                schema.negativeEvidence.putAll(negative);
                schema.verificationInFlight = false;
            }
            throw exception;
        }

        synchronized (this) {
            schema.directAlloyEvidence = direct;
            schema.negativeEvidence.putAll(negative);
            schema.negativeEvidence.put(
                    GuardDimension.PROVENANCE,
                    internalBarrierEvidence(schema, GuardDimension.PROVENANCE));
            schema.negativeEvidence.put(
                    GuardDimension.SEMANTIC_PROFILE,
                    internalBarrierEvidence(schema, GuardDimension.SEMANTIC_PROFILE));
            schema.leanEvidence = lean;
            schema.dependencies = List.copyOf(validation.dependencies());
            schema.verificationInFlight = false;
            schema.transition(State.VERIFIED_SCHEMA);
            return schema.view();
        }
    }

    /** Admits an already-proved equality as an unordered schema at a new generation. */
    public synchronized SchemaRecordView admitSchema(String schemaId) {
        SchemaRecord schema = requireSchema(schemaId, State.VERIFIED_SCHEMA);
        if (admittedSchemaCount() >= limits.maxAdmittedSchemas()) {
            throw new IllegalStateException("Adaptive admitted-schema budget exhausted");
        }
        if (generation >= limits.maxTheoryGenerations()) {
            throw new IllegalStateException("Adaptive theory-generation budget exhausted");
        }
        Set<String> affected = affectedPairKeys(schema);
        if (affected.size() > limits.maxSchemaAffectedPairs()) {
            throw new IllegalStateException(
                    "Schema would exceed the bounded affected-region saturation budget: "
                            + affected.size());
        }
        rejectInverseOrDuplicateAdmission(schema);
        generation = Math.incrementExact(generation);
        schema.generation = generation;
        schema.affectedEndpointPairs.addAll(affected);
        schema.affectedPairs = schema.affectedEndpointPairs.size();
        schema.transition(State.ADMITTED);
        admittedByRegion.computeIfAbsent(
                schema.region.stable, ignored -> new ArrayList<>()).add(schema.id);
        invalidateRegion(schema.region.stable);
        return schema.view();
    }

    public synchronized void verifyCertificate(AugmentationCertificate certificate) {
        Objects.requireNonNull(certificate, "certificate");
        if (!bootstrap.digest().equals(certificate.bootstrapDigest)) {
            throw new IllegalArgumentException(
                    "Adaptive certificate names another R0 bootstrap");
        }
        LocalRecord local = locals.get(certificate.recordId);
        if (local != null) {
            if (local.state != State.VERIFIED_LOCAL
                    || certificate.scope != AdmissionScope.INSTANCE_LOCAL
                    || !local.pair.stable.equals(certificate.endpointPair)
                    || !local.evidenceDigest().equals(certificate.evidenceDigest)
                    || !local.correspondence.digest().equals(
                            certificate.correspondenceDigest)
                    || !local.semanticContextDigest.equals(
                            certificate.semanticContextDigest)) {
                throw new IllegalArgumentException("Malformed local augmentation certificate");
            }
            return;
        }
        SchemaRecord schema = schemas.get(certificate.recordId);
        if (schema == null
                || schema.state != State.ADMITTED
                || certificate.scope != AdmissionScope.GENERALIZED_SCHEMA
                || certificate.theoryGeneration != schema.generation
                || !schema.evidenceDigest().equals(certificate.evidenceDigest)) {
            throw new IllegalArgumentException("Malformed schema augmentation certificate");
        }
        ApplicationRecord application = applications.get(certificate.digest);
        if (application == null
                || !application.endpointPair.equals(certificate.endpointPair)
                || !application.schemaId.equals(schema.id)
                || application.theoryGeneration != schema.generation
                || !application.correspondence.digest().equals(
                        certificate.correspondenceDigest)
                || !application.semanticContextDigest.equals(
                        certificate.semanticContextDigest)) {
            throw new IllegalArgumentException(
                    "Schema certificate lacks replayable source correspondence");
        }
    }

    public synchronized List<LocalRecordView> localRecords() {
        return locals.values().stream().map(LocalRecord::view).toList();
    }

    public synchronized List<SchemaRecordView> schemaRecords() {
        return schemas.values().stream().map(SchemaRecord::view).toList();
    }

    public synchronized List<ApplicationRecordView> applicationRecords() {
        return applications.values().stream().map(ApplicationRecord::view).toList();
    }

    public synchronized void writeLedger(Path output) throws IOException {
        EquivalenceAugmentationLedger.write(
                output,
                bootstrap,
                generation,
                localRecords(),
                schemaRecords(),
                applicationRecords());
    }

    private Decision adaptiveDecision(
            Operand left,
            Operand right,
            AlloyBooleanSchema.Pair semanticApplication,
            SourceEndpointCorrespondence.Evidence applicationCorrespondence,
            String applicationContextDigest) {
        PairKey pair = PairKey.of(left, right);
        if (applicationContextDigest != null && applicationCorrespondence != null) {
            for (String localId : verifiedLocalPairIndex.getOrDefault(
                    pair.stable, List.of())) {
                LocalRecord local = locals.get(localId);
                if (local.state == State.VERIFIED_LOCAL
                        && local.semanticContextDigest.equals(applicationContextDigest)
                        && local.correspondence.digest().equals(
                                applicationCorrespondence.digest())) {
                    return new Decision(new AugmentationCertificate(
                            pair.stable,
                            local.id,
                            AdmissionScope.INSTANCE_LOCAL,
                            local.generation,
                            bootstrap.digest(),
                            local.evidenceDigest(),
                            applicationCorrespondence.digest(),
                            applicationContextDigest));
                }
            }
        }

        RegionPair region = RegionPair.of(left, right);
        String cacheKey = pair.stable + "|semantic="
                + semanticApplicationDigest(semanticApplication)
                + "|correspondence="
                + (applicationCorrespondence == null
                        ? "none" : applicationCorrespondence.digest())
                + "|context="
                + (applicationContextDigest == null
                        ? "none" : applicationContextDigest);
        CachedDecision cached = comparisonCache.get(cacheKey);
        if (cached != null && cached.regionRevision == regionRevision(region.stable)) {
            return cached.decision;
        }
        List<String> candidates = admittedByRegion.getOrDefault(
                region.stable, List.of());
        if (candidates.size() > limits.maxSchemaChecksPerComparison()) {
            throw new IllegalStateException(
                    "Affected region exceeds per-comparison schema budget");
        }
        if (!comparisonCache.containsKey(cacheKey)
                && comparisonCache.size() >= limits.maxCachedComparisons()) {
            throw new IllegalStateException(
                    "Adaptive comparison-cache budget exhausted");
        }
        Decision decision = null;
        for (String id : candidates) {
            SchemaRecord schema = schemas.get(id);
            if (semanticApplication != null
                    && schema.proposal.matchesEitherDirection(left.term, right.term)
                    && schema.semanticProposal.matchesEitherDirection(
                            semanticApplication.left(), semanticApplication.right())) {
                boolean unseenPair = !schema.affectedEndpointPairs.contains(pair.stable);
                if (unseenPair
                        && schema.affectedEndpointPairs.size()
                                >= limits.maxSchemaAffectedPairs()) {
                    throw new IllegalStateException(
                            "Adaptive schema affected-pair budget exhausted");
                }
                decision = new Decision(new AugmentationCertificate(
                        pair.stable,
                        schema.id,
                        AdmissionScope.GENERALIZED_SCHEMA,
                        schema.generation,
                        bootstrap.digest(),
                        schema.evidenceDigest(),
                        applicationCorrespondence.digest(),
                        applicationContextDigest));
                if (!applications.containsKey(decision.certificate.digest())
                        && applications.size() >= limits.maxApplicationRecords()) {
                    throw new IllegalStateException(
                            "Adaptive schema-application ledger budget exhausted");
                }
                if (unseenPair) {
                    schema.affectedEndpointPairs.add(pair.stable);
                    schema.affectedPairs = schema.affectedEndpointPairs.size();
                }
                applications.putIfAbsent(
                        decision.certificate.digest(),
                        new ApplicationRecord(
                                decision.certificate.digest(),
                                pair.stable,
                                schema.id,
                                applicationCorrespondence,
                                applicationContextDigest,
                                schema.generation));
                break;
            }
        }
        comparisonCache.put(
                cacheKey,
                new CachedDecision(
                        regionRevision(region.stable),
                        region.stable,
                        pair.stable,
                        decision));
        return decision;
    }

    private long regionRevision(String region) {
        List<String> admitted = admittedByRegion.get(region);
        if (admitted == null || admitted.isEmpty()) {
            return 0;
        }
        return schemas.get(admitted.get(admitted.size() - 1)).generation;
    }

    private void invalidateRegion(String region) {
        comparisonCache.entrySet().removeIf(
                entry -> entry.getValue().region.equals(region));
    }

    private void invalidatePair(String pair) {
        comparisonCache.entrySet().removeIf(
                entry -> entry.getValue().endpointPair.equals(pair));
    }

    private static String semanticContextDigest(
            AlloyEquivalenceValidator.Request request,
            SourceEndpointCorrespondence.Evidence correspondence) {
        Objects.requireNonNull(correspondence, "correspondence");
        String effectiveExecutionContext = request.sourceCommandContext() == null
                ? "REQUEST_SCOPE/" + request.scope()
                : "SOURCE_COMMAND/" + request.sourceCommandContext().stableString();
        return AugmentationDigests.sha256(String.join("\n",
                "adaptive-semantic-context-v4-resolved-endpoint-pair",
                AugmentationDigests.sha256(request.modelSource()),
                correspondence.digest(),
                request.universalBinder(),
                request.comparisonKind().name(),
                request.expectedOutcome().name(),
                effectiveExecutionContext));
    }

    private static String semanticApplicationDigest(
            AlloyBooleanSchema.Pair application) {
        if (application == null) {
            return "none";
        }
        String left = AugmentationDigests.sha256(application.left());
        String right = AugmentationDigests.sha256(application.right());
        return AugmentationDigests.sha256(directionless(left, right));
    }

    private Set<String> affectedPairKeys(SchemaRecord schema) {
        Set<String> affected = new LinkedHashSet<>();
        for (LocalRecord local : locals.values()) {
            if (schema.region.equals(local.region)
                    && local.semanticPair != null
                    && schema.proposal.matchesEitherDirection(
                            local.left.term, local.right.term)
                    && schema.semanticProposal.matchesEitherDirection(
                            local.semanticPair.left(), local.semanticPair.right())) {
                affected.add(local.pair.stable);
            }
        }
        return affected;
    }

    private void rejectInverseOrDuplicateAdmission(SchemaRecord candidate) {
        for (String id : admittedByRegion.getOrDefault(candidate.region.stable, List.of())) {
            SchemaRecord admitted = schemas.get(id);
            boolean same = candidate.proposal.left().equals(admitted.proposal.left())
                    && candidate.proposal.right().equals(admitted.proposal.right());
            boolean inverse = candidate.proposal.left().equals(admitted.proposal.right())
                    && candidate.proposal.right().equals(admitted.proposal.left());
            boolean semanticSame = candidate.semanticProposal.left().equals(
                            admitted.semanticProposal.left())
                    && candidate.semanticProposal.right().equals(
                            admitted.semanticProposal.right());
            boolean semanticInverse = candidate.semanticProposal.left().equals(
                            admitted.semanticProposal.right())
                    && candidate.semanticProposal.right().equals(
                            admitted.semanticProposal.left());
            if ((same || inverse) && (semanticSame || semanticInverse)) {
                throw new IllegalStateException(
                        "Duplicate or inverse adaptive equality would create an orientation cycle");
            }
        }
    }

    private int admittedSchemaCount() {
        return (int) schemas.values().stream()
                .filter(schema -> schema.state == State.ADMITTED).count();
    }

    private SchemaRecord requireSchema(String id, State expected) {
        SchemaRecord schema = schemas.get(requireText(id, "schema id"));
        if (schema == null || schema.state != expected) {
            throw new IllegalArgumentException(
                    "Schema " + id + " is not in state " + expected);
        }
        return schema;
    }

    private void requireSchemaDependencies(
            SchemaRecord schema,
            List<String> dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        String bootstrapDependency = "R0:" + bootstrap.digest();
        if (!dependencies.contains(bootstrapDependency)) {
            throw new IllegalArgumentException(
                    "Every adaptive proof must bind the immutable R0 digest");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String dependency : dependencies) {
            String checked = requireText(dependency, "dependency");
            if (!unique.add(checked)) {
                throw new IllegalArgumentException("Duplicate schema dependency " + checked);
            }
            if (checked.equals(schema.id)) {
                throw new IllegalArgumentException("A schema cannot prove itself");
            }
            if (checked.equals(bootstrapDependency)) {
                continue;
            }
            SchemaRecord prior = schemas.get(checked);
            if (prior == null || prior.state != State.ADMITTED
                    || prior.generation <= 0
                    || prior.generation > schema.createdGeneration) {
                throw new IllegalArgumentException(
                        "Schema dependency is absent, unadmitted, or not earlier: " + checked);
            }
            requireAcyclicDependency(prior, schema.id, new LinkedHashSet<>());
        }
    }

    private void requireAcyclicDependency(
            SchemaRecord current,
            String target,
            Set<String> visited) {
        if (!visited.add(current.id)) {
            return;
        }
        if (current.id.equals(target)) {
            throw new IllegalArgumentException("Adaptive schema dependency cycle");
        }
        for (String dependency : current.dependencies) {
            SchemaRecord nested = schemas.get(dependency);
            if (nested != null) {
                requireAcyclicDependency(nested, target, visited);
            }
        }
    }

    private static GuardEvidence internalBarrierEvidence(
            SchemaRecord schema,
            GuardDimension dimension) {
        if (dimension != GuardDimension.PROVENANCE
                && dimension != GuardDimension.SEMANTIC_PROFILE) {
            throw new IllegalArgumentException(
                    "Only non-semantic authority barriers use internal evidence");
        }
        String detail = String.join(";",
                "prepared-endpoint-required",
                "certificate-export-session-required",
                "semantic-profile-equality-required");
        String digest = AugmentationDigests.sha256(String.join("\n",
                "adaptive-internal-guard-rejection-v1",
                schema.id,
                schema.region.stable,
                dimension.name(),
                detail));
        return new GuardEvidence(
                "AUTHORITY_REJECTION", "REJECTED", digest, detail);
    }

    private static void requireMechanicallyWeakenedGuards(
            SchemaRecord schema,
            SchemaValidation validation) {
        Set<String> witnessSources = new LinkedHashSet<>();
        for (LocalRecord witness : schema.witnesses) {
            witnessSources.add(witness.correspondence.modelSourceSha256());
        }
        String directSourceDigest = AugmentationDigests.sha256(
                validation.directAlloyValidation().modelSource());
        if (!witnessSources.contains(directSourceDigest)) {
            throw new IllegalArgumentException(
                    "Direct schema validation must use exact source bytes from a "
                            + "verified origin witness");
        }
        for (Map.Entry<GuardDimension, WeakeningProbe> entry
                : validation.weakenedGuardProbes().entrySet()) {
            WeakeningProbe probe = entry.getValue();
            String sourceDigest = AugmentationDigests.sha256(
                    probe.request().modelSource());
            if (!witnessSources.contains(sourceDigest)) {
                throw new IllegalArgumentException(
                        "A weakened-guard probe must use source bytes from a verified "
                                + "origin witness");
            }
            switch (entry.getKey()) {
                case EXACT_TYPE:
                    LocalRecord first = schema.witnesses.get(0);
                    if (probe.request().comparisonKind()
                            != AlloyEquivalenceValidator.ComparisonKind.TERM_EQUALITY) {
                        throw new IllegalArgumentException(
                                "The exact-type guard must be probed by term equality");
                    }
                    boolean sameDirection = probe.expectedLeftType().equals(
                                    first.left.outputType)
                            && probe.expectedRightType().equals(
                                    first.right.outputType);
                    boolean reverseDirection = probe.expectedLeftType().equals(
                                    first.right.outputType)
                            && probe.expectedRightType().equals(
                                    first.left.outputType);
                    if (sameDirection || reverseDirection) {
                        throw new IllegalArgumentException(
                                "The exact-type probe did not weaken the inferred type guard");
                    }
                    break;
                case OPERATOR:
                    requireOperatorWeakening(schema, probe.request());
                    break;
                case ARITY:
                    requireArityWeakening(schema, probe.request());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Only Alloy-search guard dimensions accept external probes");
            }
        }
    }

    private static void requireOperatorWeakening(
            SchemaRecord schema,
            AlloyEquivalenceValidator.Request request) {
        AlloyBooleanSchema.Pair pair = AlloyBooleanSchema.analyze(request);
        Set<String> schemaRoots = new LinkedHashSet<>();
        schemaRoots.add(patternRoot(schema.semanticProposal.left()));
        schemaRoots.add(patternRoot(schema.semanticProposal.right()));
        String leftRoot = pair.left().tag();
        String rightRoot = pair.right().tag();
        if (schemaRoots.contains(leftRoot) && schemaRoots.contains(rightRoot)) {
            throw new IllegalArgumentException(
                    "The operator probe did not weaken the inferred root-operator guard");
        }
    }

    private static void requireArityWeakening(
            SchemaRecord schema,
            AlloyEquivalenceValidator.Request request) {
        AlloyBooleanSchema.Pair pair = AlloyBooleanSchema.analyze(request);
        Set<String> schemaSignatures = Set.of(
                semanticRootSignature(schema.semanticProposal.left()),
                semanticRootSignature(schema.semanticProposal.right()));
        String left = pair.left().tag() + "/" + pair.left().children().size();
        String right = pair.right().tag() + "/" + pair.right().children().size();
        boolean sameOperator = schemaSignatures.stream().anyMatch(signature ->
                signature.startsWith(pair.left().tag() + "/"))
                && schemaSignatures.stream().anyMatch(signature ->
                        signature.startsWith(pair.right().tag() + "/"));
        if (!sameOperator
                || schemaSignatures.contains(left) && schemaSignatures.contains(right)) {
            throw new IllegalArgumentException(
                    "The arity probe must retain inferred root operators while changing "
                            + "at least one root arity");
        }
    }

    private static String semanticRootSignature(
            StructuralAntiUnifier.Pattern pattern) {
        return patternRoot(pattern) + "/" + pattern.children().size();
    }

    private static String patternRoot(StructuralAntiUnifier.Pattern pattern) {
        if (pattern.isHole()) {
            throw new IllegalArgumentException(
                    "A guarded semantic schema cannot have a root hole");
        }
        String symbol = pattern.symbol();
        String prefix = "key/";
        String suffix = "/scalars=0";
        if (!symbol.startsWith(prefix) || !symbol.endsWith(suffix)) {
            throw new IllegalArgumentException(
                    "Semantic schema root is not a parser-derived zero-scalar key");
        }
        return symbol.substring(prefix.length(), symbol.length() - suffix.length());
    }

    private static String directionless(String left, String right) {
        return left.compareTo(right) <= 0
                ? left + "\n" + right : right + "\n" + left;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requireDigest(String value, String label) {
        String checked = requireText(value, label);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is not SHA-256");
        }
        return checked;
    }

    private static final class Operand {
        private final StructuralKey term;
        private final String observationDigest;
        private final GraphType outputType;
        private final SemanticProfile semanticProfile;
        private final String profileDigest;
        private final List<RootSignature> roots;
        private final String provenanceDigest;
        private final String inputIdentifier;
        private final String inputSha256;
        private final String authorityDigest;

        private Operand(
                StructuralKey term,
                String observationDigest,
                GraphType outputType,
                SemanticProfile semanticProfile,
                String profileDigest,
                List<RootSignature> roots,
                String provenanceDigest,
                String inputIdentifier,
                String inputSha256,
                String authorityDigest) {
            this.term = term;
            this.observationDigest = observationDigest;
            this.outputType = outputType;
            this.semanticProfile = Objects.requireNonNull(
                    semanticProfile, "semanticProfile");
            this.profileDigest = profileDigest;
            this.roots = List.copyOf(roots);
            this.provenanceDigest = provenanceDigest;
            this.inputIdentifier = inputIdentifier;
            this.inputSha256 = inputSha256;
            this.authorityDigest = authorityDigest;
        }

        static Operand fromPrepared(CanonicalAlloyPipeline.Prepared prepared) {
            Objects.requireNonNull(prepared, "prepared");
            if (!prepared.retainsSemanticArtifact()
                    || !prepared.retainsCertificateExportSession()) {
                throw new IllegalArgumentException(
                        "Adaptive equality requires an uncompacted provenance-retaining "
                                + "certified endpoint");
            }
            CertifiedSemanticArtifact artifact = prepared.semanticArtifact();
            CertificateProvenance provenance = prepared
                    .certificateExportSession().provenance();
            if (!artifact.semanticProfile().equals(prepared.semanticProfile())) {
                throw new IllegalStateException(
                        "Adaptive endpoint artifact and profile disagree");
            }
            List<RootSignature> roots = new ArrayList<>();
            for (FiniteUnfoldingTree unfolding : artifact.unfoldings()) {
                roots.add(RootSignature.from(unfolding.restoredRoot()));
            }
            roots = roots.stream().distinct().sorted().toList();
            if (roots.isEmpty()) {
                throw new IllegalStateException(
                        "Adaptive endpoint has no certified complete unfolding");
            }
            String provenanceDigest = AugmentationDigests.sha256(
                    String.join("\n", provenance.identityScalars()));
            String authorityDigest = AugmentationDigests.sha256(String.join("\n",
                    "CERTIFIED_NORMALIZED_IR_WITH_INPUT_PROVENANCE",
                    AugmentationDigests.sha256(
                            prepared.certificateExportSession().finalSnapshot().stateKey()),
                    artifact.constructionSources().semanticProfile().fingerprint(),
                    provenanceDigest));
            return new Operand(
                    prepared.canonicalObservation().key(),
                    prepared.canonicalObservation().digest(),
                    artifact.root().outputType(),
                    prepared.semanticProfile(),
                    prepared.semanticProfile().fingerprint(),
                    roots,
                    provenanceDigest,
                    provenance.inputIdentifier(),
                    provenance.inputSha256(),
                    authorityDigest);
        }

        String endpointIdentity() {
            return String.join("/",
                    observationDigest,
                    profileDigest,
                    inputSha256,
                    authorityDigest);
        }
    }

    private record RootSignature(
            String operator,
            String operatorSignature,
            String outputType,
            List<Integer> arities) implements Comparable<RootSignature> {
        static RootSignature from(TypedENode node) {
            List<Integer> arities = new ArrayList<>();
            for (PortValue port : node.ports()) {
                arities.add(portArity(port));
            }
            return new RootSignature(
                    node.operator().operator(),
                    AugmentationDigests.sha256(node.operator().structuralKey()),
                    typeStable(node.outputType()),
                    List.copyOf(arities));
        }

        @Override
        public int compareTo(RootSignature other) {
            return stable().compareTo(other.stable());
        }

        String stable() {
            return operator + "|" + operatorSignature + "|" + outputType
                    + "|" + arities;
        }

        private static int portArity(PortValue port) {
            if (port instanceof OnePort) {
                return 1;
            }
            if (port instanceof SeqPort) {
                return ((SeqPort) port).elements().size();
            }
            if (port instanceof BagPort) {
                return ((BagPort) port).occurrences().size();
            }
            if (port instanceof SetPort) {
                return ((SetPort) port).elements().size();
            }
            if (port instanceof BindPort || port instanceof BindBlockPort) {
                return 1;
            }
            throw new IllegalStateException(
                    "Unknown certified port kind " + port.getClass().getName());
        }
    }

    private static final class PairKey {
        private final String stable;

        private PairKey(String stable) {
            this.stable = stable;
        }

        static PairKey of(Operand left, Operand right) {
            String a = left.endpointIdentity();
            String b = right.endpointIdentity();
            return new PairKey(a.compareTo(b) <= 0
                    ? a + "<=>" + b : b + "<=>" + a);
        }
    }

    private static final class RegionPair {
        private final OperandRegion left;
        private final OperandRegion right;
        private final String stable;

        private RegionPair(OperandRegion left, OperandRegion right) {
            if (left.stable.compareTo(right.stable) <= 0) {
                this.left = left;
                this.right = right;
            } else {
                this.left = right;
                this.right = left;
            }
            this.stable = this.left.stable + "\n<=>\n" + this.right.stable;
        }

        static RegionPair of(Operand left, Operand right) {
            return new RegionPair(OperandRegion.of(left), OperandRegion.of(right));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RegionPair
                    && stable.equals(((RegionPair) other).stable);
        }

        @Override
        public int hashCode() {
            return stable.hashCode();
        }
    }

    private static final class OperandRegion {
        private final String operators;
        private final String exactType;
        private final String arities;
        private final String profile;
        private final String authority;
        private final String stable;

        private OperandRegion(
                String operators,
                String exactType,
                String arities,
                String profile,
                String authority) {
            this.operators = operators;
            this.exactType = exactType;
            this.arities = arities;
            this.profile = profile;
            this.authority = authority;
            this.stable = String.join("\n",
                    operators, exactType, arities, profile, authority);
        }

        static OperandRegion of(Operand operand) {
            String operators = operand.roots.stream()
                    .map(root -> root.operator + "@" + root.operatorSignature)
                    .sorted().reduce((left, right) -> left + ";" + right).orElseThrow();
            String exactType = typeStable(operand.outputType);
            String arities = operand.roots.stream().map(root -> root.arities.toString())
                    .sorted().reduce((left, right) -> left + ";" + right).orElseThrow();
            return new OperandRegion(
                    operators,
                    exactType,
                    arities,
                    operand.profileDigest,
                    "CERTIFIED_NORMALIZED_IR_WITH_INPUT_PROVENANCE");
        }
    }

    private static final class GuardSet {
        private final Map<GuardDimension, String> values;
        private final String digest;

        private GuardSet(Map<GuardDimension, String> values, String digest) {
            this.values = Collections.unmodifiableMap(
                    new EnumMap<>(Objects.requireNonNull(values, "values")));
            this.digest = digest;
        }

        static GuardSet from(RegionPair region) {
            EnumMap<GuardDimension, String> values =
                    new EnumMap<>(GuardDimension.class);
            values.put(GuardDimension.OPERATOR,
                    region.left.operators + "<=>" + region.right.operators);
            values.put(GuardDimension.EXACT_TYPE,
                    region.left.exactType + "<=>" + region.right.exactType);
            values.put(GuardDimension.ARITY,
                    region.left.arities + "<=>" + region.right.arities);
            values.put(GuardDimension.SEMANTIC_PROFILE,
                    region.left.profile + "<=>" + region.right.profile);
            values.put(GuardDimension.PROVENANCE,
                    region.left.authority + "<=>" + region.right.authority);
            String stable = String.join("\n",
                    "exact-operator-guard=" + values.get(GuardDimension.OPERATOR),
                    "exact-type-guard=" + values.get(GuardDimension.EXACT_TYPE),
                    "exact-arity-guard=" + values.get(GuardDimension.ARITY),
                    "semantic-profile-guard="
                            + values.get(GuardDimension.SEMANTIC_PROFILE),
                    "provenance-guard=" + values.get(GuardDimension.PROVENANCE));
            return new GuardSet(values, AugmentationDigests.sha256(stable));
        }
    }

    private static final class LocalRecord {
        private final String id;
        private final Operand left;
        private final Operand right;
        private final PairKey pair;
        private final RegionPair region;
        private final AlloyEquivalenceValidator.Request validation;
        private final AlloyBooleanSchema.Pair semanticPair;
        private final SourceEndpointCorrespondence.Evidence correspondence;
        private final String semanticContextDigest;
        private final int distance;
        private final long generation;
        private final List<State> transitions = new ArrayList<>();
        private State state = State.OBSERVED;
        private AlloyEquivalenceValidator.Evidence alloyEvidence;
        private boolean validationInFlight;

        private LocalRecord(
                String id,
                Operand left,
                Operand right,
                PairKey pair,
                RegionPair region,
                AlloyEquivalenceValidator.Request validation,
                AlloyBooleanSchema.Pair semanticPair,
                SourceEndpointCorrespondence.Evidence correspondence,
                String semanticContextDigest,
                int distance,
                long generation) {
            this.id = id;
            this.left = left;
            this.right = right;
            this.pair = pair;
            this.region = region;
            this.validation = validation;
            this.semanticPair = semanticPair;
            this.correspondence = correspondence;
            this.semanticContextDigest = requireDigest(
                    semanticContextDigest, "semanticContextDigest");
            this.distance = distance;
            this.generation = generation;
            transitions.add(State.OBSERVED);
        }

        void transition(State next) {
            boolean legal = state == State.OBSERVED && next == State.CANDIDATE
                    || state == State.CANDIDATE
                            && (next == State.FALSIFIED || next == State.VERIFIED_LOCAL);
            if (!legal) {
                throw new IllegalStateException(
                        "Illegal local augmentation transition " + state + " -> " + next);
            }
            state = next;
            transitions.add(next);
        }

        String evidenceDigest() {
            if (alloyEvidence == null || state != State.VERIFIED_LOCAL) {
                throw new IllegalStateException("Local equality lacks verified evidence");
            }
            return AugmentationDigests.sha256(String.join("\n",
                    id,
                    pair.stable,
                    alloyEvidence.digest(),
                    semanticContextDigest,
                    Long.toString(generation)));
        }

        LocalRecordView view() {
            return new LocalRecordView(
                    id,
                    state,
                    AdmissionScope.INSTANCE_LOCAL,
                    pair.stable,
                    region.stable,
                    left.inputIdentifier,
                    left.inputSha256,
                    right.inputIdentifier,
                    right.inputSha256,
                    distance,
                    alloyEvidence == null ? "" : alloyEvidence.outcome().name(),
                    alloyEvidence == null ? "" : alloyEvidence.detail(),
                    alloyEvidence == null ? "" : alloyEvidence.digest(),
                    correspondence.digest(),
                    correspondence.detail(),
                    semanticContextDigest,
                    List.copyOf(transitions),
                    generation);
        }
    }

    private static final class SchemaRecord {
        private final String id;
        private final RegionPair region;
        private final StructuralAntiUnifier.Proposal proposal;
        private final StructuralAntiUnifier.Proposal semanticProposal;
        private final AlloyBooleanSchema.LeanObligation leanObligation;
        private final GuardSet guards;
        private final List<LocalRecord> witnesses;
        private final long createdGeneration;
        private final String schemaDigest;
        private final List<State> transitions = new ArrayList<>();
        private final EnumMap<GuardDimension, GuardEvidence>
                negativeEvidence = new EnumMap<>(GuardDimension.class);
        private State state = State.CANDIDATE;
        private AlloyEquivalenceValidator.Evidence directAlloyEvidence;
        private LeanSchemaProofValidator.Evidence leanEvidence;
        private List<String> dependencies = List.of();
        private boolean verificationInFlight;
        private long generation;
        private int affectedPairs;
        private final Set<String> affectedEndpointPairs = new LinkedHashSet<>();

        private SchemaRecord(
                String id,
                RegionPair region,
                StructuralAntiUnifier.Proposal proposal,
                StructuralAntiUnifier.Proposal semanticProposal,
                GuardSet guards,
                List<LocalRecord> witnesses,
                long createdGeneration) {
            this.id = id;
            this.region = region;
            this.proposal = proposal;
            this.semanticProposal = semanticProposal;
            this.leanObligation = AlloyBooleanSchema.LeanObligation.from(
                    semanticProposal);
            this.guards = guards;
            this.witnesses = List.copyOf(witnesses);
            this.createdGeneration = createdGeneration;
            this.schemaDigest = AugmentationDigests.sha256(String.join("\n",
                    id,
                    proposal.digest(),
                    semanticProposal.digest(),
                    leanObligation.digest(),
                    guards.digest,
                    witnesses.stream().map(record -> record.id)
                            .reduce((left, right) -> left + "\n" + right).orElseThrow()));
            transitions.add(State.CANDIDATE);
        }

        void transition(State next) {
            boolean legal = state == State.CANDIDATE
                            && (next == State.FALSIFIED || next == State.VERIFIED_SCHEMA)
                    || state == State.VERIFIED_SCHEMA && next == State.ADMITTED;
            if (!legal) {
                throw new IllegalStateException(
                        "Illegal schema augmentation transition " + state + " -> " + next);
            }
            state = next;
            transitions.add(next);
        }

        String evidenceDigest() {
            if (state != State.ADMITTED
                    || directAlloyEvidence == null
                    || leanEvidence == null
                    || negativeEvidence.size() != GuardDimension.values().length) {
                throw new IllegalStateException(
                        "Admitted schema lacks complete replay evidence");
            }
            List<String> evidence = new ArrayList<>();
            evidence.add(schemaDigest);
            evidence.add(directAlloyEvidence.digest());
            for (GuardDimension dimension : GuardDimension.values()) {
                evidence.add(dimension.name() + "="
                        + negativeEvidence.get(dimension).digest);
            }
            evidence.add(leanEvidence.digest());
            evidence.addAll(dependencies);
            evidence.add(Long.toString(generation));
            return AugmentationDigests.sha256(String.join("\n", evidence));
        }

        SchemaRecordView view() {
            EnumMap<GuardDimension, GuardEvidenceView> negatives =
                    new EnumMap<>(GuardDimension.class);
            for (Map.Entry<GuardDimension, GuardEvidence> entry
                    : negativeEvidence.entrySet()) {
                negatives.put(entry.getKey(), entry.getValue().view());
            }
            return new SchemaRecordView(
                    id,
                    state,
                    AdmissionScope.GENERALIZED_SCHEMA,
                    region.stable,
                    schemaDigest,
                    proposal.left().stableForm(),
                    proposal.right().stableForm(),
                    semanticProposal.digest(),
                    leanObligation.parameters(),
                    leanObligation.statement(),
                    guards.digest,
                    guards.values,
                    witnesses.stream().map(record -> record.id).toList(),
                    positiveEvidence(),
                    Collections.unmodifiableMap(negatives),
                    leanEvidence == null ? "" : leanEvidence.sourcePath(),
                    leanEvidence == null ? "" : leanEvidence.digest(),
                    leanEvidence == null ? "" : leanEvidence.executablePath(),
                    leanEvidence == null ? "" : leanEvidence.toolchainSha256(),
                    leanEvidence == null ? "" : leanEvidence.leanVersion(),
                    dependencies,
                    Orientation.UNORIENTED,
                    List.copyOf(transitions),
                    generation,
                    affectedPairs);
        }

        private List<String> positiveEvidence() {
            List<String> evidence = new ArrayList<>();
            for (LocalRecord witness : witnesses) {
                if (witness.alloyEvidence != null) {
                    evidence.add(witness.alloyEvidence.digest());
                }
            }
            if (directAlloyEvidence != null) {
                if (directAlloyEvidence.validatesExpectedOutcome()) {
                    evidence.add(directAlloyEvidence.digest());
                }
            }
            return List.copyOf(evidence);
        }

    }

    private record Decision(AugmentationCertificate certificate) {
    }

    private record ApplicationRecord(
            String certificateDigest,
            String endpointPair,
            String schemaId,
            SourceEndpointCorrespondence.Evidence correspondence,
            String semanticContextDigest,
            long theoryGeneration) {
        ApplicationRecordView view() {
            return new ApplicationRecordView(
                    certificateDigest,
                    endpointPair,
                    schemaId,
                    correspondence.digest(),
                    correspondence.detail(),
                    semanticContextDigest,
                    theoryGeneration);
        }
    }

    private record GuardEvidence(
            String kind,
            String outcome,
            String digest,
            String detail) {
        static GuardEvidence alloy(AlloyEquivalenceValidator.Evidence evidence) {
            return new GuardEvidence(
                    "ALLOY_COUNTEREXAMPLE_SEARCH",
                    evidence.outcome().name(),
                    evidence.digest(),
                    evidence.detail());
        }

        GuardEvidenceView view() {
            return new GuardEvidenceView(kind, outcome, digest, detail);
        }
    }

    private record CachedDecision(
            long regionRevision,
            String region,
            String endpointPair,
            Decision decision) {
    }

    private static String typeStable(GraphType type) {
        List<String> children = new ArrayList<>();
        for (GraphType argument : type.arguments()) {
            children.add(typeStable(argument));
        }
        return type.kind().name() + "(" + Objects.toString(type.symbol(), "")
                + ")[" + String.join(",", children) + "]";
    }
}
