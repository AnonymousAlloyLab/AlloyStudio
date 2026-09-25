package is.fivefivefive.CanDis;

import java.io.PrintStream;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Consistent progress, throughput, ETA, and stall reporting for experiment runners. */
public final class ExperimentProgress {
    private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final PrintStream output;
    private final String label;
    private final String units;
    private final long total;
    private final long progressStep;
    private final long startedNanos;
    private long lastProgressNanos;
    private long nextProgress;
    private long lastReported = -1;

    private ExperimentProgress(
            PrintStream output,
            String label,
            long total,
            String units,
            String detail) {
        this.output = Objects.requireNonNull(output, "output");
        this.label = requireText(label, "label");
        this.units = requireText(units, "units");
        this.total = Math.max(0L, total);
        progressStep = Math.max(1L, Math.min(1000L, Math.max(1L, (this.total + 19L) / 20L)));
        nextProgress = progressStep;
        startedNanos = System.nanoTime();
        lastProgressNanos = startedNanos;
        output.printf(Locale.ROOT, "%s: processing %,d %s%s.%n",
                this.label,
                this.total,
                this.units,
                detail == null || detail.isBlank() ? "" : " " + detail.trim());
        if (this.total == 0L) {
            report(0L, startedNanos);
        }
    }

    public static ExperimentProgress start(
            PrintStream output,
            String label,
            long total,
            String units) {
        return new ExperimentProgress(output, label, total, units, null);
    }

    public static ExperimentProgress start(
            PrintStream output,
            String label,
            long total,
            String units,
            String detail) {
        return new ExperimentProgress(output, label, total, units, detail);
    }

    public synchronized void update(long completed) {
        long bounded = bounded(completed);
        long now = System.nanoTime();
        if (bounded >= total || bounded >= nextProgress
                || now - lastProgressNanos >= HEARTBEAT_NANOS) {
            report(bounded, now);
        }
    }

    public synchronized void heartbeat(long completed, int inFlight, String detail) {
        long now = System.nanoTime();
        if (now - lastProgressNanos < HEARTBEAT_NANOS) {
            return;
        }
        long bounded = bounded(completed);
        output.printf(Locale.ROOT,
                "%s: still working; %,d/%,d complete and %,d tasks in flight%s.%n",
                label,
                bounded,
                total,
                Math.max(0, inFlight),
                detail == null || detail.isBlank() ? "" : "; " + detail.trim());
        lastProgressNanos = now;
    }

    public synchronized void finish(long completed) {
        long bounded = bounded(completed);
        if (lastReported != bounded || bounded != total) {
            report(bounded, System.nanoTime());
        }
    }

    private void report(long completed, long now) {
        long elapsedNanos = Math.max(1L, now - startedNanos);
        double seconds = elapsedNanos / 1_000_000_000.0;
        double rate = completed / seconds;
        long remainingSeconds = completed <= 0L || rate <= 0.0
                ? 0L
                : Math.round((total - completed) / rate);
        output.printf(Locale.ROOT,
                "%s: %,d/%,d complete (%.1f%%), %.2f %s/s, ETA %s.%n",
                label,
                completed,
                total,
                total == 0L ? 100.0 : 100.0 * completed / total,
                rate,
                units,
                formatDuration(remainingSeconds));
        lastReported = completed;
        lastProgressNanos = now;
        nextProgress = completed >= Long.MAX_VALUE - progressStep
                ? Long.MAX_VALUE
                : (completed / progressStep + 1L) * progressStep;
    }

    private long bounded(long completed) {
        return Math.max(0L, Math.min(total, completed));
    }

    static String formatDuration(long seconds) {
        long bounded = Math.max(0L, seconds);
        long hours = bounded / 3600L;
        long minutes = bounded % 3600L / 60L;
        long remainder = bounded % 60L;
        return hours > 0L
                ? String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, remainder)
                : String.format(Locale.ROOT, "%dm %02ds", minutes, remainder);
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
