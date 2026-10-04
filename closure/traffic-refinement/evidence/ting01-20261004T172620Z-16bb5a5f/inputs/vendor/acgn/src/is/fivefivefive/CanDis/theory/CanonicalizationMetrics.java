package is.fivefivefive.CanDis.theory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Deterministic work/size counters retained with one {@code canon_G} result. */
public record CanonicalizationMetrics(
        long inputSerializedBytes,
        long kernelSerializedBytes,
        long retainedFindOccurrences,
        long retainedParentSteps,
        long retainedContainerNormalizations,
        long globalFreeRenamingCandidates,
        long localQuotientWorkItems) {
    public CanonicalizationMetrics {
        if (inputSerializedBytes < 0
                || kernelSerializedBytes < 0
                || retainedFindOccurrences < 0
                || retainedParentSteps < 0
                || retainedContainerNormalizations < 0
                || globalFreeRenamingCandidates < 0
                || localQuotientWorkItems < 0) {
            throw new IllegalArgumentException(
                    "Canonicalization counters must be non-negative");
        }
    }

    static CanonicalizationMetrics from(
            LeaderKernelResult leaderKernel,
            long globalFreeRenamingCandidates,
            long localQuotientWorkItems) {
        Objects.requireNonNull(leaderKernel, "leaderKernel");
        LeaderKernelTrace trace = leaderKernel.trace();
        long parentSteps = 0L;
        for (TypedFindResult find : trace.findResults()) {
            parentSteps = Math.addExact(
                    parentSteps, find.parentPath().steps().size());
        }
        return new CanonicalizationMetrics(
                serializedBytes(leaderKernel.source().structuralKey()),
                serializedBytes(leaderKernel.kernel().structuralKey()),
                trace.findResults().size(),
                parentSteps,
                trace.containerNormalizations().size(),
                globalFreeRenamingCandidates,
                localQuotientWorkItems);
    }

    public long retainedTraceLength() {
        return Math.addExact(
                Math.addExact(retainedFindOccurrences, retainedParentSteps),
                retainedContainerNormalizations);
    }

    private static long serializedBytes(StructuralKey key) {
        return key.stableString().getBytes(StandardCharsets.UTF_8).length;
    }
}
