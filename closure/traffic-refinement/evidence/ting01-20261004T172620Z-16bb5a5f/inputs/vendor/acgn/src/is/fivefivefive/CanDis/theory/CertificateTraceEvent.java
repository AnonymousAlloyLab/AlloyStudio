package is.fivefivefive.CanDis.theory;

import java.util.Objects;

/** One ordered transition appended only after producer invariants hold. */
public final class CertificateTraceEvent {
    public enum Kind {
        INSERT_FRESH,
        INSERT_COLLISION,
        UNION,
        ADD_SYMMETRY,
        RESTRICT_INTERFACE,
        PATH_COMPRESSION,
        REBUILD_START,
        REBUILD_RECORD,
        REBUILD_COMPLETE
    }

    private final long sequence;
    private final Kind kind;
    private final CertificateTraceSnapshot before;
    private final CertificateTraceSnapshot after;
    private final CertificateTracePayload payload;

    public CertificateTraceEvent(
            long sequence,
            Kind kind,
            CertificateTraceSnapshot before,
            CertificateTraceSnapshot after,
            CertificateTracePayload payload) {
        if (sequence < 0) {
            throw new IllegalArgumentException("Negative trace sequence");
        }
        this.sequence = sequence;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.before = Objects.requireNonNull(before, "before");
        this.after = Objects.requireNonNull(after, "after");
        this.payload = Objects.requireNonNull(payload, "payload");
        Kind expected = expectedKind(this.payload);
        if (this.kind != expected) {
            throw new IllegalArgumentException(
                    "Trace event kind " + this.kind
                            + " cannot carry " + this.payload.getClass().getSimpleName()
                            + "; expected " + expected);
        }
        this.before.verifyConservationTo(this.after, this.payload);
    }

    public long sequence() {
        return sequence;
    }

    public Kind kind() {
        return kind;
    }

    public CertificateTraceSnapshot before() {
        return before;
    }

    public CertificateTraceSnapshot after() {
        return after;
    }

    public CertificateTracePayload payload() {
        return payload;
    }

    private static Kind expectedKind(CertificateTracePayload payload) {
        if (payload instanceof CertificateTracePayload.Insertion) {
            return ((CertificateTracePayload.Insertion) payload).result().collided()
                    ? Kind.INSERT_COLLISION : Kind.INSERT_FRESH;
        }
        if (payload instanceof CertificateTracePayload.Union) {
            return Kind.UNION;
        }
        if (payload instanceof CertificateTracePayload.Symmetry) {
            return Kind.ADD_SYMMETRY;
        }
        if (payload instanceof CertificateTracePayload.Restriction) {
            return Kind.RESTRICT_INTERFACE;
        }
        if (payload instanceof CertificateTracePayload.PathCompression) {
            return Kind.PATH_COMPRESSION;
        }
        if (payload instanceof CertificateTracePayload.RebuildStart) {
            return Kind.REBUILD_START;
        }
        if (payload instanceof CertificateTracePayload.RebuildRecord) {
            return Kind.REBUILD_RECORD;
        }
        if (payload instanceof CertificateTracePayload.RebuildComplete) {
            return Kind.REBUILD_COMPLETE;
        }
        throw new IllegalArgumentException(
                "Unknown trace payload " + payload.getClass().getName());
    }
}
