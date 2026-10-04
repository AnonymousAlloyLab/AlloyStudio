package is.fivefivefive.CanDis.theory;

/** Aggregate counters for the exact bytes emitted by one certificate bundle. */
public record CertificateWriteMetrics(
        long inputSerializedBytes,
        long kernelSerializedBytes,
        long retainedTraceLength,
        long globalFreeRenamingCandidates,
        long localQuotientWorkItems,
        long serializedCanonicalOrbitCandidates,
        long certificateBytes) {
    public CertificateWriteMetrics {
        if (inputSerializedBytes < 0
                || kernelSerializedBytes < 0
                || retainedTraceLength < 0
                || globalFreeRenamingCandidates < 0
                || localQuotientWorkItems < 0
                || serializedCanonicalOrbitCandidates < 0
                || certificateBytes < 0) {
            throw new IllegalArgumentException(
                    "Certificate write counters must be non-negative");
        }
    }
}
