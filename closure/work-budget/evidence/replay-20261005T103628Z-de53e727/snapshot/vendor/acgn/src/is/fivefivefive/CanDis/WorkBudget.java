package is.fivefivefive.CanDis;

import java.util.function.LongSupplier;

/** Request-global work fuel for one comparison (AP01-C01/C02).
 *
 * Every charge happens before the work it pays for. A charge larger than the
 * remaining fuel performs no step: it exhausts the budget and throws. Exhaustion
 * is sticky, so a caller that swallows the exception still cannot publish a
 * result; entry points must check {@link #exhausted()} before serialization.
 * Outside a budgeted request (library callers, uploads, self-tests that do not
 * opt in) charges are no-ops and behaviour is unchanged.
 */
public final class WorkBudget {
    /** Carries no request data and no stack trace. */
    public static final class Exhausted extends RuntimeException {
        private Exhausted() { super("Request work budget exhausted", null, false, false); }
    }

    private static final Exhausted EXHAUSTED = new Exhausted();
    private static final ThreadLocal<State> STATE = new ThreadLocal<>();
    private static final int CLOCK_CHECK_CALLS = 1024;

    private static final class State {
        private final long initial;
        private final long allocationLimit;
        private long remaining;
        private long allocated;
        private boolean exhausted;
        private final LongSupplier clock;
        private final long startedNanos;
        private final long limitNanos;
        private int clockChecks = CLOCK_CHECK_CALLS;
        private State(long initial, long allocationLimit) {
            this(initial, allocationLimit, null, 0);
        }
        private State(long initial, long allocationLimit, LongSupplier clock, long limitNanos) {
            this.initial = initial;
            this.remaining = initial;
            this.allocationLimit = allocationLimit;
            this.clock = clock;
            this.limitNanos = limitNanos;
            this.startedNanos = clock == null ? 0 : clock.getAsLong();
        }
    }

    private WorkBudget() { }

    /** Start one request-global budget; budgets never nest or renew. */
    public static void begin(long fuel) {
        begin(fuel, Long.MAX_VALUE);
    }

    /** As {@link #begin(long)}, also bounding cumulative retained allocations. */
    public static void begin(long fuel, long allocationLimit) {
        if (fuel < 0 || allocationLimit < 0) throw new IllegalArgumentException("Negative work budget");
        if (STATE.get() != null) throw new IllegalStateException("Work budget already active");
        STATE.set(new State(fuel, allocationLimit));
    }

    /** Cooperative elapsed-time boundary; external parsing/native work still
     * needs the owner's hard process deadline. No thread or timer is created. */
    public static void begin(long fuel, long allocationLimit, long workMillis) {
        beginTimed(fuel, allocationLimit, workMillis, System::nanoTime);
    }

    // The deterministic test clock is private and never comes from a request.
    private static void beginTimed(long fuel, long allocationLimit, long workMillis, LongSupplier clock) {
        if (fuel < 0 || allocationLimit < 0 || workMillis < 1 || workMillis > 60_000)
            throw new IllegalArgumentException("Invalid timed work budget");
        if (STATE.get() != null) throw new IllegalStateException("Work budget already active");
        STATE.set(new State(fuel, allocationLimit, clock, workMillis * 1_000_000L));
    }

    /** Mandatory at publication, even when the last phase made no charges. */
    public static void checkpoint() {
        State state = STATE.get();
        if (state == null) return;
        if (state.exhausted) throw EXHAUSTED;
        if (state.clock != null) {
            long elapsed = state.clock.getAsLong() - state.startedNanos;
            // Signed subtraction handles nanoTime wrap for supported short
            // intervals. A negative elapsed observation refuses publication.
            if (elapsed < 0 || elapsed >= state.limitNanos) {
                state.exhausted = true;
                throw EXHAUSTED;
            }
        }
    }

    private static void sampleClock(State state) {
        if (state.clock == null) return;
        state.clockChecks--;
        if (state.clockChecks == 0) {
            state.clockChecks = CLOCK_CHECK_CALLS;
            checkpoint();
        }
    }

    /** End the active budget and return the units charged, or -1 if none was active. */
    public static long end() {
        State state = STATE.get();
        STATE.remove();
        return state == null ? -1 : state.initial - state.remaining;
    }

    public static boolean active() { return STATE.get() != null; }

    public static boolean exhausted() {
        State state = STATE.get();
        return state != null && state.exhausted;
    }

    /** Retained allocation entries counted so far in the active budget, or 0 outside one. */
    public static long allocated() {
        State state = STATE.get();
        return state == null ? 0 : state.allocated;
    }

    /** Units charged so far in the active budget, or 0 outside one. */
    public static long used() {
        State state = STATE.get();
        return state == null ? 0 : state.initial - state.remaining;
    }

    /** Charge before performing {@code units} atomic operations. */
    public static void charge(long units) {
        State state = STATE.get();
        if (state == null) return;
        if (units < 0) throw new IllegalArgumentException("Negative work charge");
        if (state.exhausted || units > state.remaining) {
            state.exhausted = true;
            throw EXHAUSTED;
        }
        sampleClock(state);
        state.remaining -= units;
    }

    /** Charge work for, and count, {@code entries} retained allocations before
     * they are materialized. The cumulative count is bounded per request. */
    public static void allocate(long entries) {
        State state = STATE.get();
        if (state == null) return;
        if (entries < 0) throw new IllegalArgumentException("Negative retained allocation");
        if (entries > state.allocationLimit - state.allocated) {
            state.exhausted = true;
            throw EXHAUSTED;
        }
        charge(entries);
        state.allocated += entries;
    }

    /** Bound a retained structure before it grows past {@code limit} entries.
     * Fuel bounds work; this bounds live memory that work alone cannot. Inside a
     * budgeted request, oversize is the same sticky, no-partial-output outcome. */
    public static void requireRetained(long entries, long limit) {
        State state = STATE.get();
        if (state == null) return;
        if (entries < 0 || limit < 0) throw new IllegalArgumentException("Negative retained bound");
        if (state.exhausted || entries > limit) {
            state.exhausted = true;
            throw EXHAUSTED;
        }
    }

    /** Charge a rows x columns table before it is allocated or filled. */
    public static void chargeCells(long rows, long columns) {
        State state = STATE.get();
        if (state == null) return;
        if (rows < 0 || columns < 0) throw new IllegalArgumentException("Negative work dimension");
        // Saturating to MAX_VALUE would admit an overflowing table when that is
        // also the available fuel. Refuse before multiplication/materialization.
        if (rows != 0 && columns > Long.MAX_VALUE / rows) {
            state.exhausted = true;
            throw EXHAUSTED;
        }
        charge(rows * columns);
    }
}
