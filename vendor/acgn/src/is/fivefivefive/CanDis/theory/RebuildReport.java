package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable accounting for one finite, rewrite-disabled rebuild fixed point. */
public final class RebuildReport {
    private final int processedRecords;
    private final int changedKeys;
    private final int collisions;
    private final int certifiedUnions;
    private final int maximumDirtyRecords;
    private final List<CertificateTracePayload.Union> generatedSubtransitions;
    private final long firstEventSequence;
    private final List<CertificateTracePayload.RebuildRecord> processedTransitions;

    RebuildReport(
            int processedRecords,
            int changedKeys,
            int collisions,
            int certifiedUnions,
            int maximumDirtyRecords) {
        this(
                processedRecords,
                changedKeys,
                collisions,
                certifiedUnions,
                maximumDirtyRecords,
                Collections.emptyList(),
                -1L,
                Collections.emptyList());
    }

    RebuildReport(
            int processedRecords,
            int changedKeys,
            int collisions,
            int certifiedUnions,
            int maximumDirtyRecords,
            List<? extends CertificateTracePayload.Union> generatedSubtransitions) {
        this(
                processedRecords,
                changedKeys,
                collisions,
                certifiedUnions,
                maximumDirtyRecords,
                generatedSubtransitions,
                -1L,
                Collections.emptyList());
    }

    RebuildReport(
            int processedRecords,
            int changedKeys,
            int collisions,
            int certifiedUnions,
            int maximumDirtyRecords,
            List<? extends CertificateTracePayload.Union> generatedSubtransitions,
            long firstEventSequence,
            List<? extends CertificateTracePayload.RebuildRecord> processedTransitions) {
        this.processedRecords = processedRecords;
        this.changedKeys = changedKeys;
        this.collisions = collisions;
        this.certifiedUnions = certifiedUnions;
        this.maximumDirtyRecords = maximumDirtyRecords;
        if (processedRecords < 0 || changedKeys < 0 || collisions < 0
                || certifiedUnions < 0 || maximumDirtyRecords < 0) {
            throw new IllegalArgumentException(
                    "Rebuild accounting values must be nonnegative");
        }
        if (changedKeys > processedRecords || collisions != certifiedUnions) {
            throw new IllegalArgumentException(
                    "Rebuild accounting is inconsistent with its edit and union units");
        }
        Objects.requireNonNull(generatedSubtransitions, "generatedSubtransitions");
        List<CertificateTracePayload.Union> copy = new ArrayList<>(
                generatedSubtransitions.size());
        for (CertificateTracePayload.Union transition : generatedSubtransitions) {
            copy.add(Objects.requireNonNull(transition, "generated transition"));
        }
        if (copy.size() != certifiedUnions) {
            throw new IllegalArgumentException(
                    "Rebuild union count must equal its retained generated transitions");
        }
        this.generatedSubtransitions = Collections.unmodifiableList(copy);
        this.firstEventSequence = firstEventSequence;
        Objects.requireNonNull(processedTransitions, "processedTransitions");
        List<CertificateTracePayload.RebuildRecord> recordCopy =
                new ArrayList<>(processedTransitions.size());
        int derivedChanged = 0;
        List<CertificateTracePayload.Union> derivedNested = new ArrayList<>();
        for (CertificateTracePayload.RebuildRecord transition : processedTransitions) {
            CertificateTracePayload.RebuildRecord checked = Objects.requireNonNull(
                    transition, "processed transition");
            recordCopy.add(checked);
            derivedChanged += checked.changed() ? 1 : 0;
            derivedNested.addAll(checked.generatedSubtransitions());
        }
        if (firstEventSequence >= 0) {
            if (recordCopy.size() != processedRecords
                    || derivedChanged != changedKeys
                    || !isOrderedSubsequence(derivedNested, copy)) {
                throw new IllegalArgumentException(
                        "Rebuild report is not derived from its retained record ledger");
            }
        } else if (!recordCopy.isEmpty()) {
            throw new IllegalArgumentException(
                    "A synthetic rebuild report cannot carry an unbound event ledger");
        }
        this.processedTransitions = Collections.unmodifiableList(recordCopy);
    }

    public int processedRecords() {
        return processedRecords;
    }

    public int changedKeys() {
        return changedKeys;
    }

    public int collisions() {
        return collisions;
    }

    public int certifiedUnions() {
        return certifiedUnions;
    }

    public int maximumDirtyRecords() {
        return maximumDirtyRecords;
    }

    public List<CertificateTracePayload.Union> generatedSubtransitions() {
        return generatedSubtransitions;
    }

    public long firstEventSequence() {
        return firstEventSequence;
    }

    public List<CertificateTracePayload.RebuildRecord> processedTransitions() {
        return processedTransitions;
    }

    public boolean hasExactTransitionLedger() {
        return firstEventSequence >= 0;
    }

    private static <T> boolean isOrderedSubsequence(
            List<? extends T> expected,
            List<? extends T> actual) {
        int index = 0;
        for (T value : actual) {
            if (index < expected.size() && expected.get(index).equals(value)) {
                index++;
            }
        }
        return index == expected.size();
    }

    @Override
    public String toString() {
        return "rebuild(processed=" + processedRecords
                + ", changed=" + changedKeys
                + ", collisions=" + collisions
                + ", unions=" + certifiedUnions
                + ", maxDirty=" + maximumDirtyRecords + ")";
    }
}
