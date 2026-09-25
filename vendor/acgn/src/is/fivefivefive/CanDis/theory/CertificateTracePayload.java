package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Closed producer-side payloads retained until independent export. */
public sealed interface CertificateTracePayload permits
        CertificateTracePayload.Insertion,
        CertificateTracePayload.Union,
        CertificateTracePayload.Symmetry,
        CertificateTracePayload.Restriction,
        CertificateTracePayload.PathCompression,
        CertificateTracePayload.RebuildStart,
        CertificateTracePayload.RebuildRecord,
        CertificateTracePayload.RebuildComplete {

    /** One live owner-qualified shape and its exact EC equation. */
    record ShapeRecord(
            ParentRecordKey key,
            TypedEClassInterface owner,
            ShapeWitness witness,
            TypedEqualityCertificate ownerEquation) {
        public ShapeRecord {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(witness, "witness");
            Objects.requireNonNull(ownerEquation, "ownerEquation");
            if (!key.owner().equals(owner.id())
                    || !key.shape().exactSlots().equals(witness.exactSlots())
                    || !owner.exposedSlots().equals(witness.exposedInterface())
                    || !owner.outputType().equals(key.shape().outputType())) {
                throw new IllegalArgumentException(
                        "Shape-record evidence has inconsistent owner or witness metadata");
            }
            CertificateVerifier.verify(ownerEquation);
            TypedCertificateEndpoint shapeEndpoint = TypedCertificateEndpoint.node(
                    key.shape().node().act(witness.instantiatingRenaming()));
            TypedCertificateEndpoint ownerEndpoint = TypedCertificateEndpoint.invocation(
                    new TypedInvocation(
                            owner,
                            TypedEmbedding.inclusion(
                                    owner.exposedSlots(), witness.ambientSupport())));
            EqualityCertificates.orient(ownerEquation, shapeEndpoint, ownerEndpoint);
        }
    }

    /** One old live shape rehomed as one exact replacement record. */
    record ShapeRehome(ShapeRecord original, ShapeRecord replacement) {
        public ShapeRehome {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(replacement, "replacement");
        }
    }

    /** One installed parent edge and every shape disposition it generated. */
    record Union(
            ParentEdgeCertificate certificate,
            List<ShapeRehome> rehomes,
            List<RetiredShapeRecordCertificate> retirements,
            boolean revisionIncrement)
            implements CertificateTracePayload {
        public Union {
            Objects.requireNonNull(certificate, "certificate");
            CertificateVerifier.verifyParentEdge(certificate);
            rehomes = immutable(rehomes, "rehome");
            retirements = immutable(retirements, "retirement");
            for (ShapeRehome rehome : rehomes) {
                if (!rehome.original().key().owner().equals(certificate.child().id())
                        || !rehome.replacement().key().owner().equals(
                                certificate.parent().id())
                        || !rehome.original().owner().equals(certificate.child())
                        || !rehome.replacement().owner().equals(certificate.parent())
                        || !rehome.original().key().shape().equals(
                                rehome.replacement().key().shape())) {
                    throw new IllegalArgumentException(
                            "A union rehome must preserve the exact shape and follow its parent edge");
                }
            }
            requireCanonicalRehomeOrder(rehomes);
            for (RetiredShapeRecordCertificate retirement : retirements) {
                retirement.verify();
                if (retirement.cause()
                                != RetiredShapeRecordCertificate.Cause.OWNER_UNION
                        || retirement.installedParentEdge().isEmpty()
                        || !retirement.installedParentEdge().get().equals(certificate)) {
                    throw new IllegalArgumentException(
                            "A union retirement must name the installed parent edge");
                }
            }
            requireCanonicalRetirementOrder(retirements);
        }

        public Union(
                ParentEdgeCertificate certificate,
                List<ShapeRehome> rehomes,
                List<RetiredShapeRecordCertificate> retirements) {
            this(certificate, rehomes, retirements, false);
        }

        /** Compatibility constructor for unsupported synthetic writer fixtures. */
        public Union(ParentEdgeCertificate certificate) {
            this(certificate, Collections.emptyList(), Collections.emptyList(), false);
        }

        Union withRevisionIncrement() {
            if (revisionIncrement) {
                return this;
            }
            return new Union(certificate, rehomes, retirements, true);
        }

        public boolean hasRecordConservationEvidence() {
            return !rehomes.isEmpty() || !retirements.isEmpty();
        }

        private static void requireCanonicalRehomeOrder(List<ShapeRehome> values) {
            ParentRecordKey prior = null;
            for (ShapeRehome value : values) {
                ParentRecordKey current = value.original().key();
                if (prior != null && prior.compareTo(current) >= 0) {
                    throw new IllegalArgumentException(
                            "Union rehomes must use strict canonical old-record order");
                }
                prior = current;
            }
        }

        private static void requireCanonicalRetirementOrder(
                List<RetiredShapeRecordCertificate> values) {
            ParentRecordKey prior = null;
            for (RetiredShapeRecordCertificate value : values) {
                ParentRecordKey current = value.retiredRecord();
                if (prior != null && prior.compareTo(current) >= 0) {
                    throw new IllegalArgumentException(
                            "Union retirements must use strict canonical old-record order");
                }
                prior = current;
            }
        }
    }

    record Insertion(
            CertifiedInsertionResult result,
            List<Union> generatedSubtransitions) implements CertificateTracePayload {
        public Insertion {
            Objects.requireNonNull(result, "result");
            generatedSubtransitions = immutable(
                    generatedSubtransitions, "generated union");
            if (result.collided() && generatedSubtransitions.isEmpty()) {
                throw new IllegalArgumentException(
                        "A colliding insertion must retain its generated union transition");
            }
            if (result.collided()
                    && !generatedSubtransitions.get(0).certificate().equals(
                            result.collisionEdge().orElseThrow())) {
                throw new IllegalArgumentException(
                        "The first insertion subtransition must install its collision edge");
            }
        }

        public Insertion(CertifiedInsertionResult result) {
            this(requireNoncolliding(result), Collections.emptyList());
        }

        private static CertifiedInsertionResult requireNoncolliding(
                CertifiedInsertionResult result) {
            CertifiedInsertionResult checked = Objects.requireNonNull(result, "result");
            if (checked.collided()) {
                throw new IllegalArgumentException(
                        "Use the exact insertion payload for a colliding insertion");
            }
            return checked;
        }
    }

    record Symmetry(EClassId eclass, SymmetryCertificate certificate)
            implements CertificateTracePayload {
        public Symmetry {
            Objects.requireNonNull(eclass, "eclass");
            Objects.requireNonNull(certificate, "certificate");
        }
    }

    record Restriction(InterfaceRestrictionCertificate certificate)
            implements CertificateTracePayload {
        public Restriction {
            Objects.requireNonNull(certificate, "certificate");
        }
    }

    record PathCompression(TypedFindResult result) implements CertificateTracePayload {
        public PathCompression {
            Objects.requireNonNull(result, "result");
        }
    }

    /** Exact no-op boundary emitted when one successful rebuild attempt begins. */
    record RebuildStart(StructuralKey initialStateKey)
            implements CertificateTracePayload {
        public RebuildStart {
            Objects.requireNonNull(initialStateKey, "initialStateKey");
        }
    }

    /** Exact transaction for one dirty record, including unions generated inside it. */
    final class RebuildRecord implements CertificateTracePayload {
        private final ParentRecordKey original;
        private final ShapeRecord originalRecord;
        private final CanonicalizationResult canonicalization;
        private final TypedEqualityCertificate rebuildRoot;
        private final ShapeRecord replacementRecord;
        private final RetiredShapeRecordCertificate retirement;
        private final List<Union> generatedSubtransitions;
        private final boolean changed;
        private final boolean collision;
        private final boolean union;

        public RebuildRecord(
                ShapeRecord originalRecord,
                CanonicalizationResult canonicalization,
                TypedEqualityCertificate rebuildRoot,
                ShapeRecord replacementRecord,
                RetiredShapeRecordCertificate retirement,
                List<Union> generatedSubtransitions,
                boolean changed) {
            this.originalRecord = Objects.requireNonNull(
                    originalRecord, "originalRecord");
            this.original = originalRecord.key();
            this.canonicalization = Objects.requireNonNull(
                    canonicalization, "canonicalization");
            this.rebuildRoot = Objects.requireNonNull(rebuildRoot, "rebuildRoot");
            this.replacementRecord = replacementRecord;
            this.retirement = retirement;
            this.generatedSubtransitions = immutable(
                    generatedSubtransitions, "generated union");
            this.changed = changed;
            this.collision = !this.generatedSubtransitions.isEmpty();
            this.union = this.collision;
            verifyExactEvidence();
        }

        /** Compatibility constructor for unsupported synthetic writer fixtures. */
        public RebuildRecord(
                ParentRecordKey original,
                boolean changed,
                boolean collision,
                boolean union) {
            this.original = Objects.requireNonNull(original, "original");
            this.originalRecord = null;
            this.canonicalization = null;
            this.rebuildRoot = null;
            this.replacementRecord = null;
            this.retirement = null;
            this.generatedSubtransitions = Collections.emptyList();
            this.changed = changed;
            this.collision = collision;
            this.union = union;
        }

        private void verifyExactEvidence() {
            CertificateVerifier.verify(rebuildRoot);
            TypedCertificateEndpoint oldNode = TypedCertificateEndpoint.node(
                    original.shape().node().act(
                            originalRecord.witness().instantiatingRenaming()));
            TypedCertificateEndpoint rebuiltNode = TypedCertificateEndpoint.node(
                    canonicalization.shape().node().act(
                            canonicalization.ambientTransport()));
            if (!canonicalization.source().equals(
                            original.shape().node().act(
                                    originalRecord.witness().instantiatingRenaming()))) {
                throw new IllegalArgumentException(
                        "Rebuild canonicalization starts from another stored occurrence");
            }
            EqualityCertificates.orient(rebuildRoot, oldNode, rebuiltNode);
            if ((replacementRecord == null) == (retirement == null)) {
                throw new IllegalArgumentException(
                        "A rebuild must publish one replacement or one retirement");
            }
            if (replacementRecord != null) {
                if (!replacementRecord.key().owner().equals(original.owner())
                        || !replacementRecord.key().shape().equals(
                                canonicalization.shape())
                        || !replacementRecord.witness().instantiatingRenaming().equals(
                                canonicalization.witness())) {
                    throw new IllegalArgumentException(
                            "Rebuild replacement does not match its canonicalization result");
                }
            } else {
                retirement.verify();
                if (retirement.cause()
                                != RetiredShapeRecordCertificate.Cause.REBUILD_DUPLICATE
                        || !retirement.retiredRecord().equals(original)) {
                    throw new IllegalArgumentException(
                            "Rebuild retirement does not account for the original record");
                }
            }
            ShapeRecord candidate = replacementRecord != null
                    ? replacementRecord : retirement.replacementShapeRecord();
            boolean expectedChanged = !original.shape().equals(
                            canonicalization.shape())
                    || !originalRecord.witness().equals(candidate.witness())
                    || retirement != null
                    || !generatedSubtransitions.isEmpty();
            if (changed != expectedChanged) {
                throw new IllegalArgumentException(
                        "Rebuild changed flag is not derived from its exact transition");
            }
        }

        public ParentRecordKey original() {
            return original;
        }

        public ShapeRecord originalRecord() {
            return originalRecord;
        }

        public CanonicalizationResult canonicalization() {
            return canonicalization;
        }

        public TypedEqualityCertificate rebuildRoot() {
            return rebuildRoot;
        }

        public ShapeRecord replacementRecord() {
            return replacementRecord;
        }

        public RetiredShapeRecordCertificate retirement() {
            return retirement;
        }

        public List<Union> generatedSubtransitions() {
            return generatedSubtransitions;
        }

        public boolean hasExactEvidence() {
            return originalRecord != null;
        }

        public boolean changed() {
            return changed;
        }

        public boolean collision() {
            return collision;
        }

        public boolean union() {
            return union;
        }
    }

    record RebuildComplete(RebuildReport report) implements CertificateTracePayload {
        public RebuildComplete {
            Objects.requireNonNull(report, "report");
            if (!report.hasExactTransitionLedger()) {
                throw new IllegalArgumentException(
                        "A rebuild completion requires an exact retained event ledger");
            }
        }
    }

    private static <T> List<T> immutable(List<? extends T> values, String label) {
        Objects.requireNonNull(values, label + "s");
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, label));
        }
        return Collections.unmodifiableList(copy);
    }
}
