package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-memory proof-retaining trace used only by explicit verification sessions. */
public final class RecordingCertificateTraceSink implements CertificateTraceSink {
    private final List<CertificateTraceEvent> events = new ArrayList<>();
    private long openRebuildStart = -1;

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public synchronized void append(CertificateTraceEvent event) {
        if (event.sequence() != events.size()) {
            throw new IllegalStateException("Certificate trace sequence is not consecutive");
        }
        if (!events.isEmpty()) {
            CertificateTraceEvent prior = events.get(events.size() - 1);
            if (!prior.after().stateKey().equals(event.before().stateKey())) {
                throw new IllegalStateException(
                        "Certificate trace has a state discontinuity");
            }
        }
        boolean start = event.payload() instanceof CertificateTracePayload.RebuildStart;
        boolean record = event.payload() instanceof CertificateTracePayload.RebuildRecord;
        boolean completion =
                event.payload() instanceof CertificateTracePayload.RebuildComplete;
        boolean union = event.payload() instanceof CertificateTracePayload.Union;
        if (start) {
            if (openRebuildStart >= 0) {
                throw new IllegalStateException(
                        "A certificate trace cannot nest rebuild intervals");
            }
        } else if (openRebuildStart >= 0) {
            if (!record && !union && !completion) {
                throw new IllegalStateException(
                        "An unrelated transition interrupts the open rebuild interval");
            }
        } else if (record || completion) {
            throw new IllegalStateException(
                    "A rebuild record or completion lacks its retained start boundary");
        }
        if (union) {
            boolean revisionIncrement =
                    ((CertificateTracePayload.Union) event.payload())
                            .revisionIncrement();
            if (revisionIncrement == (openRebuildStart >= 0)) {
                throw new IllegalStateException(
                        "A union revision effect is inconsistent with its trace context");
            }
        }
        if (event.payload() instanceof CertificateTracePayload.RebuildComplete) {
            RebuildReport report =
                    ((CertificateTracePayload.RebuildComplete) event.payload()).report();
            if (report.firstEventSequence() != openRebuildStart) {
                throw new IllegalStateException(
                        "A rebuild completion names another start boundary");
            }
            verifyRebuildLedger(
                    report,
                    event);
        }
        events.add(event);
        if (start) {
            openRebuildStart = event.sequence();
        } else if (completion) {
            openRebuildStart = -1;
        }
    }

    private void verifyRebuildLedger(
            RebuildReport report,
            CertificateTraceEvent completion) {
        long first = report.firstEventSequence();
        if (first < 0 || first >= events.size()) {
            throw new IllegalStateException(
                    "Rebuild report names an invalid event interval");
        }
        CertificateTraceEvent start = events.get(Math.toIntExact(first));
        if (!(start.payload() instanceof CertificateTracePayload.RebuildStart)
                || !start.before().stateKey().equals(
                        ((CertificateTracePayload.RebuildStart) start.payload())
                                .initialStateKey())
                || !start.before().stateKey().equals(start.after().stateKey())) {
            throw new IllegalStateException(
                    "Rebuild report does not begin at its exact start boundary");
        }
        List<CertificateTracePayload.RebuildRecord> records = new ArrayList<>();
        List<CertificateTracePayload.Union> unions = new ArrayList<>();
        int maximumDirty = start.before().dirtyParents().size();
        for (int index = Math.incrementExact(Math.toIntExact(first));
                index < events.size(); index++) {
            CertificateTraceEvent event = events.get(index);
            maximumDirty = Math.max(
                    maximumDirty, event.before().dirtyParents().size());
            if (event.payload() instanceof CertificateTracePayload.RebuildRecord) {
                CertificateTracePayload.RebuildRecord record =
                        (CertificateTracePayload.RebuildRecord) event.payload();
                records.add(record);
                unions.addAll(record.generatedSubtransitions());
            } else if (event.payload() instanceof CertificateTracePayload.Union) {
                unions.add((CertificateTracePayload.Union) event.payload());
            } else {
                throw new IllegalStateException(
                        "A rebuild event interval contains an unrelated transition");
            }
        }
        if (!records.equals(report.processedTransitions())
                || !unions.equals(report.generatedSubtransitions())
                || records.size() != report.processedRecords()
                || maximumDirty != report.maximumDirtyRecords()) {
            throw new IllegalStateException(
                    "Rebuild completion accounting does not match its event interval");
        }
    }

    public synchronized List<CertificateTraceEvent> events() {
        if (openRebuildStart >= 0) {
            throw new IllegalStateException(
                    "A certificate trace cannot be observed with an open rebuild interval");
        }
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}
