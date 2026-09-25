package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import is.fivefivefive.CanDis.canonical.CanonicalObservation;
import is.fivefivefive.CanDis.canonical.CanonicalRepresentativeTreeDistance;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.metric.RepairProjection;
import is.fivefivefive.CanDis.metric.RepairView;
import is.fivefivefive.CanDis.theory.CertifiedSemanticArtifact;
import is.fivefivefive.CanDis.theory.CertificateExportSession;
import is.fivefivefive.CanDis.theory.CertificateProvenance;
import is.fivefivefive.CanDis.theory.CertificateTheoryManifest;
import is.fivefivefive.CanDis.theory.IndependentCertificateVerifier;
import is.fivefivefive.CanDis.theory.RecordingCertificateTraceSink;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.augmentation.AlloyEquivalenceValidator;
import is.fivefivefive.CanDis.augmentation.EquivalenceAugmenter;

/**
 * Three-layer Alloy boundary: certificate-producing semantics, canonical
 * equality, and the established repair metric evaluated over admissible
 * alignments. Standalone replay can be requested through
 * {@link #distanceEvaluationWithStandaloneReplay(Prepared, Prepared,
 * IndependentCertificateVerifier.Policy)}, but a producer-observed subprocess
 * result is not a substitute for verifier output captured by an independent
 * assessor.
 * Canonical representative TED remains available only as an explicitly named
 * diagnostic baseline.
 */
public final class CanonicalAlloyPipeline {
    public static final String PIPELINE_VERSION = "canonical-alloy-pipeline-v38-phase-local-bindings";
    public static final String MEASUREMENT_PROJECTION_VERSION = RepairProjection.VERSION;
    public static final String REPRESENTATIVE_TED_VERSION =
            CanonicalRepresentativeTreeDistance.VERSION;
    public static final String QUOTIENT_METRIC_VERSION = QuotientRepairDistance.VERSION;

    private CanonicalAlloyPipeline() {
    }

    /** Fixed-profile compatibility mode used by the historical experiment geometry. */
    public static Prepared prepare(Multigraph graph) {
        return prepareCompatibility(graph);
    }

    public static Prepared prepareCompatibility(Multigraph graph) {
        return prepare(Canonical.prepare(graph));
    }

    public static Prepared prepare(Multigraph graph, SemanticProfile semanticProfile) {
        return prepare(Canonical.prepare(graph, semanticProfile));
    }

    public static Prepared prepare(Canonical.Prepared normalized) {
        Objects.requireNonNull(normalized, "normalized");
        TheoryAlloyAdapter.Result result = TheoryAlloyAdapter.adapt(
                normalized.normalizedForms(), normalized.semanticProfile());
        return new Prepared(normalized, result);
    }

    /**
     * Explicit proof-retaining preparation. Callers must export before using
     * {@link Prepared#compactForComparison()}.
     */
    public static Prepared prepareForVerification(
            Multigraph graph,
            SemanticProfile semanticProfile) {
        Objects.requireNonNull(semanticProfile, "semanticProfile")
                .requireCertificateExportAuthority(false);
        Canonical.Prepared normalized = Canonical.prepare(graph, semanticProfile);
        byte[] normalizedInput = String.join(
                "\n", Canonical.irTemporalFol(normalized))
                .getBytes(StandardCharsets.UTF_8);
        return prepareForVerification(
                normalized,
                "canonical-normalized-ir",
                normalizedInput,
                false);
    }

    public static Prepared prepareForVerification(Canonical.Prepared normalized) {
        Objects.requireNonNull(normalized, "normalized").semanticProfile()
                .requireCertificateExportAuthority(false);
        byte[] normalizedInput = String.join(
                "\n", Canonical.irTemporalFol(normalized))
                .getBytes(StandardCharsets.UTF_8);
        return prepareForVerification(
                normalized,
                "canonical-normalized-ir",
                normalizedInput,
                false);
    }

    /** Explicit test-only bridge for the fixed compatibility profile. */
    public static Prepared prepareCompatibilityForVerification(Multigraph graph) {
        Canonical.Prepared normalized = Canonical.prepare(graph);
        byte[] normalizedInput = String.join(
                "\n", Canonical.irTemporalFol(normalized))
                .getBytes(StandardCharsets.UTF_8);
        return prepareForVerification(
                normalized,
                "canonical-normalized-ir",
                normalizedInput,
                true);
    }

    /**
     * Proof-retaining preparation whose metadata records the supplied input
     * artifact. The checked semantic derivation starts at the normalized IR
     * produced by {@link Canonical#prepare(Multigraph)}; the input bytes are
     * provenance and are not a proof of the raw-source normalization step.
     */
    public static Prepared prepareForVerification(
            Multigraph graph,
            SemanticProfile semanticProfile,
            String inputIdentifier,
            byte[] inputContent) {
        Objects.requireNonNull(semanticProfile, "semanticProfile")
                .requireCertificateExportAuthority(false);
        return prepareForVerification(
                Canonical.prepare(graph, semanticProfile),
                inputIdentifier,
                inputContent,
                false);
    }

    /** Explicit test-only bridge for fixed-profile certificate fixtures. */
    public static Prepared prepareCompatibilityForVerification(
            Multigraph graph,
            String inputIdentifier,
            byte[] inputContent) {
        return prepareForVerification(
                Canonical.prepare(graph), inputIdentifier, inputContent, true);
    }

    private static Prepared prepareForVerification(
            Canonical.Prepared normalized,
            String inputIdentifier,
            byte[] inputContent,
            boolean compatibilityTestOnly) {
        Objects.requireNonNull(normalized, "normalized");
        Objects.requireNonNull(inputContent, "inputContent");
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        CertificateProvenance provenance;
        try {
            provenance = CertificateProvenance.capture(
                    inputIdentifier,
                    inputContent,
                    PIPELINE_VERSION + ";" + CertificateTheoryManifest.VERSION);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Certificate provenance could not be recorded", exception);
        }
        if (compatibilityTestOnly) {
            if (!normalized.semanticProfile().isFixedCompatibilityProfile()
                    || !provenance.testOnly()) {
                throw new IllegalStateException(
                        "Compatibility certificate preparation is test-only");
            }
        } else {
            normalized.semanticProfile().requireCertificateExportAuthority(false);
        }
        TheoryAlloyAdapter.Result result = TheoryAlloyAdapter.adaptForVerification(
                normalized.normalizedForms(), normalized.semanticProfile(), sink, provenance);
        return new Prepared(normalized, result);
    }

    /**
     * Primary fast repair metric. Its geometry is specified by CanonicalDistance;
     * its kernel guard is an in-process consistency check, not independent replay.
     */
    public static int distance(Prepared left, Prepared right) {
        return distanceEvaluation(left, right).distance();
    }

    public static QuotientRepairDistance.Result distanceEvaluation(
            Prepared left,
            Prepared right) {
        requirePrepared(left, right);
        QuotientRepairDistance.Result result = QuotientRepairDistance.evaluate(
                left.repairView, right.repairView);
        if (left.observation.equivalentTo(right.observation)
                != (result.distance() == 0)) {
            throw new IllegalStateException(
                    "Repair-view and canonical-observation equality disagree");
        }
        return result;
    }

    /**
     * Explicit adaptive overlay evaluation. The ordinary R0 metric remains the
     * default; this path can additionally replay verified local or admitted
     * schema equality from the supplied augmenter.
     */
    public static EquivalenceAugmenter.Evaluation augmentedDistanceEvaluation(
            Prepared left,
            Prepared right,
            EquivalenceAugmenter augmenter) {
        requirePrepared(left, right);
        return Objects.requireNonNull(augmenter, "augmenter").evaluate(left, right);
    }

    /** Schema-aware adaptive evaluation with source-level correspondence. */
    public static EquivalenceAugmenter.Evaluation augmentedDistanceEvaluation(
            Prepared left,
            Prepared right,
            EquivalenceAugmenter augmenter,
            AlloyEquivalenceValidator.Request application) {
        requirePrepared(left, right);
        return Objects.requireNonNull(augmenter, "augmenter")
                .evaluate(left, right, Objects.requireNonNull(application, "application"));
    }

    /**
     * Records a positive-distance semantic miss through direct Alloy
     * validation. This never adds a handwritten R0 rewrite or orients the
     * resulting equality.
     */
    public static EquivalenceAugmenter.LocalRecordView observeAugmentedEquality(
            Prepared left,
            Prepared right,
            EquivalenceAugmenter augmenter,
            AlloyEquivalenceValidator.Request validation) {
        requirePrepared(left, right);
        return Objects.requireNonNull(augmenter, "augmenter")
                .observeEquivalent(left, right, validation);
    }

    /** Records a positive semantic miss and immediately re-evaluates locally. */
    public static EquivalenceAugmenter.Evaluation augmentEquivalentInFlight(
            Prepared left,
            Prepared right,
            EquivalenceAugmenter augmenter,
            AlloyEquivalenceValidator.Request validation) {
        requirePrepared(left, right);
        return Objects.requireNonNull(augmenter, "augmenter")
                .evaluateAndObserveEquivalent(
                        left,
                        right,
                        Objects.requireNonNull(validation, "validation"));
    }

    public enum StandaloneReplayScope {
        NORMALIZED_IR_ENDPOINTS,
        NORMALIZED_IR_ZERO_KERNEL,
        TEST_ONLY_NORMALIZED_IR_ENDPOINTS,
        TEST_ONLY_NORMALIZED_IR_ZERO_KERNEL
    }

    public record StandaloneReplayDistance(
            QuotientRepairDistance.Result metric,
            StandaloneReplayScope scope,
            String verifierSha256,
            String theoryDigest,
            IndependentCertificateVerifier.Result pairResult) {
        public StandaloneReplayDistance {
            Objects.requireNonNull(metric, "metric");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(verifierSha256, "verifierSha256");
            Objects.requireNonNull(theoryDigest, "theoryDigest");
            Objects.requireNonNull(pairResult, "pairResult");
            boolean zeroScope = scope == StandaloneReplayScope.NORMALIZED_IR_ZERO_KERNEL
                    || scope == StandaloneReplayScope.TEST_ONLY_NORMALIZED_IR_ZERO_KERNEL;
            if (zeroScope != (metric.distance() == 0 && pairResult.verified())) {
                throw new IllegalArgumentException(
                        "Independent zero-kernel scope requires a verified zero pair");
            }
        }
    }

    /**
     * Replays exact exported bundles with a digest-pinned standalone verifier.
     * The authority begins at the normalized IR endpoint; raw Alloy-to-IR
     * normalization remains outside this certificate slice. Nonzero results
     * replay both endpoints, but do not claim a proof of semantic inequality.
     * Because this method runs inside the producer process, its return value is
     * evidence for testing and diagnostics only. Independent certification uses
     * the same bundles and verifier JAR from outside this process.
     */
    public static StandaloneReplayDistance distanceEvaluationWithStandaloneReplay(
            Prepared left,
            Prepared right,
            IndependentCertificateVerifier.Policy policy) throws IOException {
        requirePrepared(left, right);
        Objects.requireNonNull(policy, "policy");
        CertificateExportSession leftSession = requireExportSession(left);
        CertificateExportSession rightSession = requireExportSession(right);
        if (!policy.expectedVerifierSha256().equals(
                        leftSession.provenance().verifierJarSha256())
                || !policy.expectedVerifierSha256().equals(
                        rightSession.provenance().verifierJarSha256())) {
            throw new IllegalArgumentException(
                    "Replay verifier digest differs from bundle provenance");
        }
        boolean leftTestOnly = leftSession.provenance().testOnly();
        boolean rightTestOnly = rightSession.provenance().testOnly();
        if (leftTestOnly != rightTestOnly) {
            throw new IllegalArgumentException(
                    "Independent comparison cannot mix test-only and publication evidence");
        }
        if (leftTestOnly && !policy.allowTestOnlyEvidence()) {
            throw new IllegalArgumentException(
                    "The independent-verifier policy rejects test-only evidence");
        }
        if (!leftTestOnly) {
            left.semanticProfile.requireCertificateExportAuthority(false);
            right.semanticProfile.requireCertificateExportAuthority(false);
        }

        QuotientRepairDistance.Result metric = distanceEvaluation(left, right);
        Path directory = Files.createTempDirectory("acgn-independent-distance-");
        try {
            Path leftBundle = directory.resolve("left.acgncert");
            Path rightBundle = directory.resolve("right.acgncert");
            leftSession.write(leftBundle);
            rightSession.write(rightBundle);
            IndependentCertificateVerifier verifier =
                    new IndependentCertificateVerifier(policy, directory.resolve("verifier"));
            IndependentCertificateVerifier.Result pair = verifier.verify(
                    IndependentCertificateVerifier.Profile.PAIR,
                    List.of(leftBundle, rightBundle));
            if (pair.outcome() == IndependentCertificateVerifier.Outcome.REJECTED) {
                throw new IOException(
                        "Standalone pair verification rejected the evidence: "
                                + pair.code() + ": " + pair.detail());
            }
            if (metric.distance() == 0 && !pair.verified()) {
                throw new IOException(
                        "Zero repair distance lacks independently replayed equality: "
                                + pair.code() + ": " + pair.detail());
            }
            if (metric.distance() != 0 && pair.verified()) {
                throw new IllegalStateException(
                        "Independent replay proves one kernel but the repair distance is nonzero");
            }
            if (metric.distance() != 0) {
                requireFullVerification(verifier, leftBundle, "left");
                requireFullVerification(verifier, rightBundle, "right");
            }
            StandaloneReplayScope scope;
            if (leftTestOnly) {
                scope = metric.distance() == 0
                        ? StandaloneReplayScope.TEST_ONLY_NORMALIZED_IR_ZERO_KERNEL
                        : StandaloneReplayScope.TEST_ONLY_NORMALIZED_IR_ENDPOINTS;
            } else {
                scope = metric.distance() == 0
                        ? StandaloneReplayScope.NORMALIZED_IR_ZERO_KERNEL
                        : StandaloneReplayScope.NORMALIZED_IR_ENDPOINTS;
            }
            return new StandaloneReplayDistance(
                    metric,
                    scope,
                    policy.expectedVerifierSha256(),
                    policy.trustedTheoryDigest(),
                    pair);
        } finally {
            deleteTemporaryTree(directory);
        }
    }

    private static CertificateExportSession requireExportSession(Prepared prepared) {
        if (!prepared.retainsCertificateExportSession()) {
            throw new IllegalArgumentException(
                    "Independent replay requires prepareForVerification and an uncompacted value");
        }
        return prepared.certificateExportSession();
    }

    private static void requireFullVerification(
            IndependentCertificateVerifier verifier,
            Path bundle,
            String side) throws IOException {
        IndependentCertificateVerifier.Result result = verifier.verify(
                IndependentCertificateVerifier.Profile.FULL, List.of(bundle));
        if (!result.verified()) {
            throw new IOException(
                    "Standalone FULL verification did not verify the " + side + " endpoint: "
                            + result.code() + ": " + result.detail());
        }
    }

    private static void deleteTemporaryTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        IOException failure = null;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Diagnostic baseline retained to expose canonical-representative discontinuity. */
    public static int canonicalRepresentativeTreeDistance(Prepared left, Prepared right) {
        requirePrepared(left, right);
        return CanonicalRepresentativeTreeDistance.distance(
                left.observation, right.observation);
    }

    private static void requirePrepared(Prepared left, Prepared right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!left.semanticProfile.equals(right.semanticProfile)) {
            throw new IllegalArgumentException(
                    "Canonical observations from different semantic profiles "
                            + "cannot be compared");
        }
    }

    public static final class Prepared {
        private final CertifiedSemanticArtifact semanticArtifact;
        private final SemanticProfile semanticProfile;
        private final CanonicalObservation observation;
        private final RepairView repairView;
        private final int representativeTreeSize;
        private final int repairObservationSize;
        private final long eclasses;
        private final long enodes;
        private final long slots;
        private final long rebuilds;
        private final long estimatedBytes;
        private final long constructionNanos;
        private final long unfoldingNanos;
        private final long observationNanos;
        private final long repairProjectionNanos;
        private final CertificateExportSession exportSession;

        private Prepared(
                Canonical.Prepared normalized,
                TheoryAlloyAdapter.Result result) {
            semanticArtifact = result.semanticArtifact();
            semanticProfile = normalized.semanticProfile();
            if (!semanticProfile.equals(semanticArtifact.semanticProfile())) {
                throw new IllegalStateException(
                        "Normalized source and certified artifact profiles differ");
            }
            observation = new CanonicalObservation(result.canonicalKey());
            representativeTreeSize = CanonicalRepresentativeTreeDistance.size(observation);
            long projectionStarted = System.nanoTime();
            repairView = RepairProjection.project(
                    result,
                    normalized.normalizedForms());
            repairProjectionNanos = System.nanoTime() - projectionStarted;
            exportSession = result.retainsCertificateExportSession()
                    ? result.certificateExportSession() : null;
            repairObservationSize = repairView.semanticSize();
            eclasses = result.eclasses();
            enodes = result.enodes();
            slots = result.slots();
            rebuilds = result.rebuilds();
            estimatedBytes = result.estimatedBytes();
            constructionNanos = result.constructionNanos();
            unfoldingNanos = result.unfoldingNanos();
            observationNanos = result.observationNanos();
        }

        private Prepared(Prepared source) {
            semanticArtifact = null;
            semanticProfile = source.semanticProfile;
            observation = source.observation;
            repairView = source.repairView;
            representativeTreeSize = source.representativeTreeSize;
            repairObservationSize = source.repairObservationSize;
            eclasses = source.eclasses;
            enodes = source.enodes;
            slots = source.slots;
            rebuilds = source.rebuilds;
            estimatedBytes = source.estimatedBytes;
            constructionNanos = source.constructionNanos;
            unfoldingNanos = source.unfoldingNanos;
            observationNanos = source.observationNanos;
            repairProjectionNanos = source.repairProjectionNanos;
            exportSession = null;
        }

        public CertifiedSemanticArtifact semanticArtifact() {
            if (semanticArtifact == null) {
                throw new IllegalStateException(
                        "The compact comparison representation does not retain the construction artifact");
            }
            return semanticArtifact;
        }

        /** Whether this value still owns the proof-heavy construction artifact. */
        public boolean retainsSemanticArtifact() {
            return semanticArtifact != null;
        }

        public boolean retainsCertificateExportSession() {
            return exportSession != null;
        }

        public CertificateExportSession certificateExportSession() {
            if (exportSession == null) {
                throw new IllegalStateException(
                        "This value was not prepared through prepareForVerification, "
                                + "or it has been compacted");
            }
            return exportSession;
        }

        /**
         * Drops construction-only graph witnesses after their certified observation and
         * repair projection have been produced. Equality and every distance operation are
         * unchanged; callers needing certificate replay must retain the original value.
         */
        public Prepared compactForComparison() {
            return semanticArtifact == null ? this : new Prepared(this);
        }

        public CanonicalObservation canonicalObservation() {
            return observation;
        }

        public SemanticProfile semanticProfile() {
            return semanticProfile;
        }

        public RepairView repairView() {
            return repairView;
        }

        /** Size of the established metric's certified repair observation. */
        public int repairObservationSize() {
            return repairObservationSize;
        }

        /** Size of the diagnostic canonical-representative TED tree. */
        public int representativeTreeSize() {
            return representativeTreeSize;
        }

        /**
         * Compatibility alias for the former exact measurement size. New
         * repair-distance normalization must use {@link #repairObservationSize()}.
         */
        public int representationSize() {
            return representativeTreeSize;
        }

        public long eclassCount() {
            return eclasses;
        }

        public long enodeCount() {
            return enodes;
        }

        public long slotCount() {
            return slots;
        }

        public long rebuildCount() {
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

        public long repairProjectionNanos() {
            return repairProjectionNanos;
        }

        public String digest() {
            return observation.digest();
        }

        public String stableForm() {
            return observation.stableForm();
        }

        /** Stable diagnostic projection consumed only by representative TED. */
        public String measurementForm() {
            return CanonicalRepresentativeTreeDistance.projection(observation).stableString();
        }

        public boolean equivalentTo(Prepared other) {
            if (other == null) {
                return false;
            }
            requirePrepared(this, other);
            return observation.equivalentTo(other.observation);
        }
    }
}
