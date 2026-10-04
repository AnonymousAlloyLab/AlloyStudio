package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Evidence that one old live shape was conserved by a live duplicate. */
public final class RetiredShapeRecordCertificate {
    public enum Cause {
        OWNER_UNION,
        REBUILD_DUPLICATE
    }

    private final Cause cause;
    private final CertificateTracePayload.ShapeRecord retired;
    private final CertificateTracePayload.ShapeRecord replacement;
    private final CertificateTracePayload.ShapeRecord retained;
    private final ParentEdgeCertificate parentEdge;
    private final CanonicalizationResult rebuildResult;
    private final TypedEqualityCertificate rebuildRoot;
    private final StructuralKey structuralKey;

    private RetiredShapeRecordCertificate(
            Cause cause,
            CertificateTracePayload.ShapeRecord retired,
            CertificateTracePayload.ShapeRecord replacement,
            CertificateTracePayload.ShapeRecord retained,
            ParentEdgeCertificate parentEdge,
            CanonicalizationResult rebuildResult,
            TypedEqualityCertificate rebuildRoot) {
        this.cause = Objects.requireNonNull(cause, "cause");
        this.retired = Objects.requireNonNull(retired, "retired");
        this.replacement = Objects.requireNonNull(replacement, "replacement");
        this.retained = Objects.requireNonNull(retained, "retained");
        this.parentEdge = parentEdge;
        this.rebuildResult = rebuildResult;
        this.rebuildRoot = rebuildRoot;
        verify();

        List<StructuralKey> parts = new ArrayList<>();
        parts.add(StructuralKey.leaf("retirement-cause", cause.name()));
        parts.add(shapeRecordKey(retired));
        parts.add(shapeRecordKey(replacement));
        parts.add(shapeRecordKey(retained));
        if (parentEdge != null) {
            parts.add(parentEdge.structuralKey());
        }
        if (rebuildResult != null) {
            parts.add(rebuildResult.structuralKey());
        }
        if (rebuildRoot != null) {
            parts.add(rebuildRoot.structuralKey());
        }
        structuralKey = StructuralKey.branch(
                "retired-owner-qualified-shape-record-v2", parts);
    }

    static RetiredShapeRecordCertificate ownerUnion(
            CertificateTracePayload.ShapeRecord retired,
            CertificateTracePayload.ShapeRecord replacement,
            CertificateTracePayload.ShapeRecord retained,
            ParentEdgeCertificate parentEdge) {
        return new RetiredShapeRecordCertificate(
                Cause.OWNER_UNION,
                retired,
                replacement,
                retained,
                Objects.requireNonNull(parentEdge, "parentEdge"),
                null,
                null);
    }

    static RetiredShapeRecordCertificate rebuildDuplicate(
            CertificateTracePayload.ShapeRecord retired,
            CertificateTracePayload.ShapeRecord replacement,
            CertificateTracePayload.ShapeRecord retained,
            CanonicalizationResult rebuildResult,
            TypedEqualityCertificate rebuildRoot) {
        return new RetiredShapeRecordCertificate(
                Cause.REBUILD_DUPLICATE,
                retired,
                replacement,
                retained,
                null,
                Objects.requireNonNull(rebuildResult, "rebuildResult"),
                Objects.requireNonNull(rebuildRoot, "rebuildRoot"));
    }

    public Cause cause() {
        return cause;
    }

    public ParentRecordKey retiredRecord() {
        return retired.key();
    }

    public ParentRecordKey replacementRecord() {
        return replacement.key();
    }

    public ParentRecordKey retainedRecord() {
        return retained.key();
    }

    public CertificateTracePayload.ShapeRecord retiredShapeRecord() {
        return retired;
    }

    public CertificateTracePayload.ShapeRecord replacementShapeRecord() {
        return replacement;
    }

    public CertificateTracePayload.ShapeRecord retainedShapeRecord() {
        return retained;
    }

    /** Existing union-only accessor retained for source compatibility. */
    public ParentEdgeCertificate parentEdge() {
        if (parentEdge == null) {
            throw new IllegalStateException(
                    "A rebuild retirement has no installed parent edge");
        }
        return parentEdge;
    }

    public Optional<ParentEdgeCertificate> installedParentEdge() {
        return Optional.ofNullable(parentEdge);
    }

    public Optional<CanonicalizationResult> rebuildResult() {
        return Optional.ofNullable(rebuildResult);
    }

    public Optional<TypedEqualityCertificate> rebuildRoot() {
        return Optional.ofNullable(rebuildRoot);
    }

    public TypedEClassInterface retiredOwner() {
        return retired.owner();
    }

    public TypedEClassInterface retainedOwner() {
        return retained.owner();
    }

    public ShapeWitness retiredWitness() {
        return retired.witness();
    }

    public ShapeWitness replacementWitness() {
        return replacement.witness();
    }

    public ShapeWitness retainedWitness() {
        return retained.witness();
    }

    public TypedEqualityCertificate retiredEquation() {
        return retired.ownerEquation();
    }

    /** Candidate replacement equation, historically called the transferred equation. */
    public TypedEqualityCertificate transferredEquation() {
        return replacement.ownerEquation();
    }

    public TypedEqualityCertificate replacementEquation() {
        return replacement.ownerEquation();
    }

    public TypedEqualityCertificate retainedEquation() {
        return retained.ownerEquation();
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    public void verify() {
        if (!replacement.key().equals(retained.key())) {
            throw new IllegalArgumentException(
                    "Retirement replacement and retained records must have one identity");
        }
        if (cause == Cause.OWNER_UNION) {
            verifyOwnerUnion();
        } else {
            verifyRebuildDuplicate();
        }
    }

    private void verifyOwnerUnion() {
        if (parentEdge == null || rebuildResult != null || rebuildRoot != null) {
            throw new IllegalArgumentException(
                    "Owner-union retirement has the wrong transition evidence");
        }
        CertificateVerifier.verifyParentEdge(parentEdge);
        if (!retired.key().shape().equals(replacement.key().shape())
                || !retired.key().owner().equals(parentEdge.child().id())
                || !replacement.key().owner().equals(parentEdge.parent().id())
                || !retired.owner().equals(parentEdge.child())
                || !replacement.owner().equals(parentEdge.parent())
                || !retained.owner().equals(parentEdge.parent())) {
            throw new IllegalArgumentException(
                    "Owner-union retirement does not follow one exact parent edge");
        }
    }

    private void verifyRebuildDuplicate() {
        if (parentEdge != null || rebuildResult == null || rebuildRoot == null) {
            throw new IllegalArgumentException(
                    "Rebuild retirement has the wrong transition evidence");
        }
        CertificateVerifier.verify(rebuildRoot);
        if (!retired.key().owner().equals(replacement.key().owner())
                || !retired.owner().equals(replacement.owner())
                || !replacement.owner().equals(retained.owner())
                || !replacement.key().shape().equals(rebuildResult.shape())
                || !replacement.witness().instantiatingRenaming().equals(
                        rebuildResult.witness())) {
            throw new IllegalArgumentException(
                    "Rebuild retirement does not bind its old, candidate, and retained records");
        }
        TypedCertificateEndpoint oldNode = TypedCertificateEndpoint.node(
                retired.key().shape().node().act(
                        retired.witness().instantiatingRenaming()));
        TypedCertificateEndpoint rebuiltNode = TypedCertificateEndpoint.node(
                rebuildResult.shape().node().act(
                        rebuildResult.ambientTransport()));
        if (!rebuildResult.source().equals(
                retired.key().shape().node().act(
                        retired.witness().instantiatingRenaming()))) {
            throw new IllegalArgumentException(
                    "Rebuild retirement starts from another old occurrence");
        }
        EqualityCertificates.orient(rebuildRoot, oldNode, rebuiltNode);
    }

    private static StructuralKey shapeRecordKey(
            CertificateTracePayload.ShapeRecord record) {
        return StructuralKey.branch(
                "retirement-shape-record",
                List.of(
                        record.key().structuralKey(),
                        TheoryKeys.eclass(record.owner()),
                        record.witness().structuralKey(),
                        record.ownerEquation().structuralKey()));
    }
}
