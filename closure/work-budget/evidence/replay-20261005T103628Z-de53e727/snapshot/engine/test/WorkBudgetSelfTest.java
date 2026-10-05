package live;

import is.fivefivefive.CanDis.WorkBudget;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** AP01-C01/C02 finite runtime checks for the request-global work budget.
 *
 * Mirrors Work.lean: zero fuel cannot act, every executed prefix fits the initial
 * fuel, exhaustion publishes no partial payload, and a completed bounded run is
 * byte-identical to the unbounded run. These are regression witnesses, not a
 * refinement proof of the Java implementation.
 */
public final class WorkBudgetSelfTest {
    private static final String ENV = "module budget_fixture\nsig A { r: set A }\n";
    private static final Set<String> PARTIAL = Set.of("distance", "breakdown", "operations", "canonicalForm",
            "comparison", "trace", "operationSummary", "astSize", "canonicalSize");
    private static int checks;

    public static void main(String[] args) {
        PrintStream report = System.out, errors = System.err;
        try (PrintStream discard = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(discard);
            System.setErr(discard);
            primitives();
            watchdog();
            responseBoundary();
            for (String metric : List.of("canonical", "ast")) {
                boundary(metric, "some x : A | x in x.r", List.of("no A", "some x : A | x in x.r", "some A"));
                boundary(metric, "all x : A | some x.r", List.of("some A", "all y : A | some y.r"));
                laterCloserCandidateCannotBeTruncated(metric);
            }
            retainedClosureBound();
            productionConstants();
        } finally {
            System.setOut(report);
            System.setErr(errors);
        }
        report.println("WorkBudgetSelfTest passed (" + checks + " checks)");
    }

    private static void primitives() {
        check(!WorkBudget.active(), "no ambient budget");
        WorkBudget.charge(1_000_000);  // outside a budget: no-op
        check(WorkBudget.used() == 0, "unbudgeted charge is a no-op");

        WorkBudget.begin(0);
        check(exhausts(() -> WorkBudget.charge(1)), "zero fuel cannot act");
        check(WorkBudget.exhausted(), "exhaustion is sticky");
        check(exhausts(() -> WorkBudget.charge(0)), "no step after exhaustion, even a free one");
        check(WorkBudget.end() == 0, "a refused step consumes nothing");

        WorkBudget.begin(5);
        WorkBudget.charge(2);
        WorkBudget.charge(3);
        check(WorkBudget.used() == 5 && !WorkBudget.exhausted(), "exact initial fuel is usable");
        check(exhausts(() -> WorkBudget.charge(1)), "fuel + 1 is refused");
        check(WorkBudget.end() == 5, "executed prefix never exceeds initial fuel");

        WorkBudget.begin(5);
        WorkBudget.charge(2);
        check(exhausts(() -> WorkBudget.charge(4)), "a batch larger than remaining fuel is refused");
        check(WorkBudget.end() == 2, "refused batches do not count as executed work");

        WorkBudget.begin(Long.MAX_VALUE);
        check(exhausts(() -> WorkBudget.chargeCells(Long.MAX_VALUE / 2, 3)), "cell products saturate, never wrap");
        check(WorkBudget.end() == 0, "an overflowing table is refused even with maximum fuel");

        WorkBudget.begin(100, 10);
        WorkBudget.allocate(10);
        check(exhausts(() -> WorkBudget.allocate(1)), "retained allocation limit is enforced before materialization");
        check(WorkBudget.end() == 10, "refused allocation does not charge work");

        WorkBudget.begin(100);
        check(exhausts(() -> WorkBudget.requireRetained(11, 10)), "retained structure bound");
        WorkBudget.end();

        WorkBudget.begin(1);
        check(threw(() -> WorkBudget.begin(1)), "budgets never nest or renew");
        WorkBudget.end();
        check(!WorkBudget.active(), "end releases the request budget");
    }

    private static void responseBoundary() {
        // A JSON string has 11 bytes of field/quote overhead here. The old
        // character check admitted 400k three-byte characters (> 1 MiB).
        check(LiveFeedback.responseFits(new JSONObject().put("text", "a".repeat(
                LiveFeedback.RESPONSE_BYTE_LIMIT - 11))), "exact byte limit is accepted");
        check(!LiveFeedback.responseFits(new JSONObject().put("text", "a".repeat(
                LiveFeedback.RESPONSE_BYTE_LIMIT - 10))), "byte limit plus one is rejected");
        check(!LiveFeedback.responseFits(new JSONObject().put("text", "界".repeat(400_000))),
                "Unicode cannot bypass the wire byte limit");
        check(LiveFeedback.responseFits(new JSONObject().put("text", "界".repeat(100_000))),
                "ordinary Unicode responses are preserved");
    }

    private static void timed(long fuel, long millis, LongSupplier clock) {
        try {
            var method = WorkBudget.class.getDeclaredMethod("beginTimed", long.class, long.class,
                    long.class, LongSupplier.class);
            method.setAccessible(true);
            method.invoke(null, fuel, 1_000_000L, millis, clock);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Deterministic watchdog fixture failed", failure);
        }
    }

    private static void watchdog() {
        AtomicLong now = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        LongSupplier clock = () -> { reads.incrementAndGet(); return now.get(); };
        timed(10_000, 1, clock);
        now.set(1_000_000);
        for (int i = 0; i < 1023; i++) WorkBudget.charge(1);
        check(reads.get() == 1, "clock is sampled at a bounded cadence, not every node");
        check(exhausts(() -> WorkBudget.charge(1)), "expired clock refuses the 1024th charge before work");
        check(reads.get() == 2 && WorkBudget.used() == 1023, "deadline refusal does not consume work");
        now.set(0);
        check(exhausts(WorkBudget::checkpoint), "clock rollback cannot revive an exhausted budget");
        check(WorkBudget.end() == 1023, "timed budget retains exact executed accounting");

        now.set(0);
        timed(10, 1, now::get);
        WorkBudget.charge(1);
        now.set(1_000_000);
        check(exhausts(WorkBudget::checkpoint), "publication checkpoint detects expiry below sampling cadence");
        WorkBudget.end();

        now.set(2_000_000);
        timed(10, 1, now::get);
        now.set(1_999_999);
        check(exhausts(WorkBudget::checkpoint), "negative elapsed time refuses publication");
        WorkBudget.end();

        now.set(Long.MAX_VALUE - 1000);
        timed(10, 1, now::get);
        now.set(Long.MIN_VALUE + 1000);
        WorkBudget.checkpoint();
        check(!WorkBudget.exhausted(), "nanoTime signed wrap preserves short positive elapsed intervals");
        now.set(Long.MIN_VALUE + 1_100_000);
        check(exhausts(WorkBudget::checkpoint), "deadline still expires across nanoTime signed wrap");
        WorkBudget.end();
    }

    /** budget-1 / budget / budget+1 around the exact deterministic requirement. */
    private static void boundary(String metric, String learner, List<String> pool) {
        JSONObject request = request(learner, pool, metric);
        JSONObject unbounded = LiveFeedback.evaluate(request, Long.MAX_VALUE);
        long required = LiveFeedback.lastWorkUnits();
        check(unbounded.getString("status").equals("ok"), metric + " fixture completes");
        LiveFeedback.evaluate(request(learner, pool, metric), Long.MAX_VALUE);
        check(LiveFeedback.lastWorkUnits() == required, metric + " work units are deterministic");

        JSONObject exact = LiveFeedback.evaluate(request(learner, pool, metric), required);
        check(exact.similar(unbounded), metric + " budget = requirement preserves the complete payload");
        JSONObject spare = LiveFeedback.evaluate(request(learner, pool, metric), required + 1);
        check(spare.similar(unbounded), metric + " budget + 1 preserves the complete payload");
        check(LiveFeedback.lastWorkUnits() == required, metric + " spare fuel is not consumed");

        for (long fuel : new long[] {required - 1, required / 2, required / 10, 100, 1, 0}) {
            JSONObject limited = LiveFeedback.evaluate(request(learner, pool, metric), fuel);
            check(isWorkLimit(limited, metric), metric + " budget " + fuel + " yields an explicit work limit");
            check(LiveFeedback.lastWorkUnits() <= fuel, metric + " exhausted prefix fits the initial fuel");
        }
        check(request.similar(request(learner, pool, metric)), metric + " request is not mutated");
    }

    /** The Work.lean [9, 0] witness: a short budget must not publish the first candidate. */
    private static void laterCloserCandidateCannotBeTruncated(String metric) {
        String learner = "some x : A | x in x.r";
        List<String> pool = List.of("no A and all x : A | no x.r and some A", learner);
        JSONObject complete = LiveFeedback.evaluate(request(learner, pool, metric), Long.MAX_VALUE);
        long required = LiveFeedback.lastWorkUnits();
        check(complete.getInt("distance") == 0, metric + " complete scan finds the later exact candidate");
        for (long fuel = 0; fuel < required; fuel += Math.max(1, required / 97)) {
            JSONObject limited = LiveFeedback.evaluate(request(learner, pool, metric), fuel);
            check(isWorkLimit(limited, metric), metric + " no truncated winner at budget " + fuel);
        }
    }

    /** n interchangeable quantifiers generate n! slot permutations; the closure is bounded. */
    private static void retainedClosureBound() {
        StringBuilder names = new StringBuilder(), body = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0) { names.append(", "); body.append(" and "); }
            names.append("a").append(i);
            body.append("a").append(i).append(" in a").append((i + 1) % 12).append(".r");
        }
        String learner = "all " + names + " : A | " + body;
        JSONObject result = LiveFeedback.evaluate(request(learner, List.of("some A"), "canonical"));
        check(isWorkLimit(result, "canonical"), "symmetric quantifier closure is rejected before materialization");
    }

    private static void productionConstants() {
        check(LiveFeedback.WORK_BUDGET > 0 && LiveFeedback.ALLOCATION_LIMIT > 0
                && LiveFeedback.ALLOCATION_LIMIT < Long.MAX_VALUE, "finite production limits");
    }

    private static boolean isWorkLimit(JSONObject result, String metric) {
        if (!result.getString("status").equals("unsupported")) return false;
        JSONArray diagnostics = result.getJSONArray("diagnostics");
        if (diagnostics.length() != 1 || !diagnostics.getJSONObject(0).getString("code").equals("WORK_LIMIT")) return false;
        String expected = metric.equals("ast") ? AstFeedback.METRIC : LiveFeedback.METRIC;
        if (!expected.equals(result.getString("metric"))) return false;
        for (String key : result.keySet()) if (PARTIAL.contains(key)) return false;
        return true;
    }

    private static JSONObject request(String learner, List<String> pool, String metric) {
        String prefix = ENV + "pred inv {\n", suffix = "\n}\n";
        return new JSONObject().put("studentSource", prefix + learner + suffix)
                .put("referenceBodies", new JSONArray(pool)).put("referencePrefix", prefix)
                .put("referenceSuffix", suffix).put("predicate", "inv").put("metric", metric);
    }

    private static boolean exhausts(Runnable action) {
        try { action.run(); return false; } catch (WorkBudget.Exhausted expected) { return true; }
    }

    private static boolean threw(Runnable action) {
        try { action.run(); return false; } catch (IllegalStateException expected) { return true; }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
